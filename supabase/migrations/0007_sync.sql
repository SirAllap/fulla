-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- Sync: push and pull of transactions.
--
-- The rules, in one place, because each one has already been learned the hard
-- way somewhere:
--
--   * client_updated_at (the editing phone's clock) decides who wins a
--     collision. server_seq (assigned here, in commit order) is what a pull
--     pages on. They are never interchangeable.
--
--   * A push does not return a cursor. The only cursor a phone may store is
--     the one a pull hands back, which is the highest server_seq among the
--     rows that pull actually returned. A stamp minted for a push is above
--     rows the other phones wrote since this one last pulled, and storing it
--     would skip those rows forever.
--
--   * A delete is applied without comparing clocks. It is not a field edit,
--     and clocks on two phones are never in step.
--
--   * A push is one request and at most 100 mutations; each mutation runs in
--     its own savepoint, so one bad row fails alone.

/*
 * Applies a batch of mutations from one phone.
 *
 *   p_mutations: [{"mutation_id", "type": "upsert"|"delete", "client_id",
 *                  "client_updated_at", "base_client_updated_at", "transaction": {...}}]
 *
 * Returns {"results": [{"mutation_id", "ok", "applied", "transaction_id",
 *                       "note"?, "conflict"?, "warnings", "error"?}]}.
 *
 * ok true and applied false is a settled no-op the phone should treat as
 * synced: a delete of something already deleted, or an edit older than the
 * stored one. Whenever a stored row exists and was not changed, the answer
 * carries it as `server_transaction` and the phone adopts it at once; an edit
 * refused as older also names the winner in `conflict`.
 */
create function public.fulla_sync_push(p_household_id uuid, p_mutations jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_me fulla.members := fulla.require_member(p_household_id, 'member');
  v_mut jsonb;
  v_mutation_id text;
  v_type text;
  v_tx jsonb;
  v_id uuid;
  v_stamp timestamptz;
  v_base text;
  v_prior fulla.transactions;
  v_prior_json jsonb;
  v_checked jsonb;
  v_new jsonb;
  v_result jsonb;
  v_results jsonb := '[]'::jsonb;
  v_state text;
  v_message text;
  v_detail text;
begin
  if p_mutations is null or jsonb_typeof(p_mutations) <> 'array' then
    perform fulla.invalid('`mutations` must be a list.');
  end if;
  if jsonb_array_length(p_mutations) > 100 then
    perform fulla.invalid('At most 100 mutations per push.');
  end if;

  for v_mut in select value from jsonb_array_elements(p_mutations) loop
    v_mutation_id := coalesce(v_mut ->> 'mutation_id', '');
    v_id := null;
    begin
      v_type := v_mut ->> 'type';
      v_tx := v_mut -> 'transaction';
      if v_type not in ('upsert', 'delete') then
        perform fulla.invalid('`type` must be upsert or delete.');
      end if;
      if jsonb_typeof(v_tx) <> 'object' then
        perform fulla.invalid('`transaction` must be an object.');
      end if;
      v_id := fulla.json_uuid(v_tx, 'id');
      if v_id is null then
        perform fulla.invalid('`transaction.id` is required.');
      end if;
      begin
        v_stamp := (v_mut ->> 'client_updated_at')::timestamptz;
      exception when others then
        v_stamp := null;
      end;
      if v_stamp is null then
        perform fulla.invalid('`client_updated_at` must be an ISO-8601 timestamp.');
      end if;
      v_base := v_mut ->> 'base_client_updated_at';

      select * into v_prior from fulla.transactions t where t.id = v_id for update;
      if v_prior.id is not null and v_prior.household_id <> p_household_id then
        perform fulla.fail(409, 'already_exists', 'That id is already in use.');
      end if;

      if v_type = 'delete' then
        if v_prior.id is null then
          v_result := jsonb_build_object('ok', true, 'applied', false, 'note', 'not_found');
        elsif v_prior.status = 'deleted' then
          -- Settled, and the phone adopts the stored tombstone: its own copy
          -- may differ in more than status, and would come back different if
          -- anyone undid the deletion.
          v_result := jsonb_build_object('ok', true, 'applied', false, 'note', 'already_deleted',
                                         'server_transaction', fulla.tx_json(v_prior));
        else
          update fulla.transactions t
             set status = 'deleted', client_updated_at = v_stamp, updated_by_user_id = auth.uid()
           where t.id = v_id;
          v_result := jsonb_build_object('ok', true, 'applied', true);
        end if;

      elsif v_prior.id is null then
        v_checked := fulla.validate_transaction(p_household_id, v_tx, '{}'::jsonb);
        v_new := v_checked -> 'tx';
        insert into fulla.transactions (
          id, household_id, kind, date, amount_minor, category_id, account_id, to_account_id,
          paid_by_member_id, to_member_id, split, recurrence, note, tags, extras, status,
          recurring_rule_id, occurrence_date, import_fingerprint, original_amount_minor, original_currency,
          created_at, client_updated_at, created_by_member_id, updated_by_user_id
        ) values (
          v_id, p_household_id, v_new ->> 'kind', (v_new ->> 'date')::date, (v_new ->> 'amount_minor')::bigint,
          (v_new ->> 'category_id')::uuid, (v_new ->> 'account_id')::uuid, (v_new ->> 'to_account_id')::uuid,
          (v_new ->> 'paid_by_member_id')::uuid, (v_new ->> 'to_member_id')::uuid,
          nullif(v_new -> 'split', 'null'::jsonb), v_new ->> 'recurrence', v_new ->> 'note',
          array(select jsonb_array_elements_text(v_new -> 'tags')), v_new -> 'extras', v_new ->> 'status',
          (v_new ->> 'recurring_rule_id')::uuid, (v_new ->> 'occurrence_date')::date, v_new ->> 'import_fingerprint',
          (v_new ->> 'original_amount_minor')::bigint, v_new ->> 'original_currency',
          coalesce((v_tx ->> 'created_at')::timestamptz, v_stamp), v_stamp, v_me.id, auth.uid()
        );
        v_result := jsonb_build_object('ok', true, 'applied', true, 'warnings', v_checked -> 'warnings');

      else
        v_prior_json := fulla.tx_json(v_prior);
        v_checked := fulla.validate_transaction(p_household_id, v_tx, v_prior.extras);
        v_new := v_checked -> 'tx';
        if v_stamp < v_prior.client_updated_at then
          -- The stored version is newer: it stays, and the phone is told what it lost.
          -- The stored row comes back too: the phone must adopt it now,
          -- because it may already be behind that phone's pull cursor and no
          -- pull would ever bring it again.
          v_result := jsonb_build_object(
            'ok', true, 'applied', false, 'warnings', v_checked -> 'warnings',
            'server_transaction', v_prior_json,
            'conflict', jsonb_build_object(
              'winner', 'server',
              'server_updated_at', fulla.iso(v_prior.client_updated_at),
              'client_updated_at', fulla.iso(v_stamp),
              'overwritten', fulla.tx_diff(v_new, v_prior_json)));
        else
          update fulla.transactions t
             set kind = v_new ->> 'kind', date = (v_new ->> 'date')::date,
                 amount_minor = (v_new ->> 'amount_minor')::bigint,
                 category_id = (v_new ->> 'category_id')::uuid, account_id = (v_new ->> 'account_id')::uuid,
                 to_account_id = (v_new ->> 'to_account_id')::uuid,
                 paid_by_member_id = (v_new ->> 'paid_by_member_id')::uuid,
                 to_member_id = (v_new ->> 'to_member_id')::uuid,
                 split = nullif(v_new -> 'split', 'null'::jsonb), recurrence = v_new ->> 'recurrence',
                 note = v_new ->> 'note', tags = array(select jsonb_array_elements_text(v_new -> 'tags')),
                 extras = v_new -> 'extras', status = v_new ->> 'status',
                 recurring_rule_id = (v_new ->> 'recurring_rule_id')::uuid,
                 occurrence_date = (v_new ->> 'occurrence_date')::date,
                 import_fingerprint = v_new ->> 'import_fingerprint',
                 original_amount_minor = (v_new ->> 'original_amount_minor')::bigint,
                 original_currency = v_new ->> 'original_currency',
                 client_updated_at = v_stamp, updated_by_user_id = auth.uid()
           where t.id = v_id;
          v_result := jsonb_build_object('ok', true, 'applied', true, 'warnings', v_checked -> 'warnings');
          -- Somebody else's edit landed between this phone's read and its
          -- write. The phone's version wins (it is newer), but it is told.
          if v_mut ? 'base_client_updated_at'
             and (v_base is null or v_base::timestamptz is distinct from v_prior.client_updated_at) then
            v_result := v_result || jsonb_build_object('conflict', jsonb_build_object(
              'winner', 'client',
              'server_updated_at', fulla.iso(v_prior.client_updated_at),
              'client_updated_at', fulla.iso(v_stamp),
              'overwritten', fulla.tx_diff(v_prior_json, v_new)));
          end if;
        end if;
      end if;

    exception when others then
      get stacked diagnostics v_state = returned_sqlstate, v_message = message_text, v_detail = pg_exception_detail;
      v_result := jsonb_build_object('ok', false, 'applied', false, 'error', jsonb_build_object(
        'code', case
                  when v_state like 'PT%' and v_detail is not null then v_detail
                  when v_state = '23505' then 'duplicate'
                  else 'validation_failed'
                end,
        'message', v_message));
    end;

    v_results := v_results || (v_result || jsonb_build_object(
      'mutation_id', v_mutation_id, 'transaction_id', v_id,
      'warnings', coalesce(v_result -> 'warnings', '[]'::jsonb)));
  end loop;

  return jsonb_build_object('results', v_results);
end;
$$;

/*
 * Transactions changed since a cursor, oldest change first, at most p_limit.
 *
 * `cursor` is the highest server_seq among the rows returned, or p_since when
 * there are none: never a clock reading and never the table's maximum.
 * `has_more` says whether to ask again straight away. The full config comes
 * along whenever the phone's config_version is not the current one, so it
 * learns about a new field before it stores a value for it.
 */
create function public.fulla_sync_pull(
  p_household_id uuid, p_since bigint default 0, p_config_version integer default null, p_limit integer default 500
) returns jsonb
language plpgsql stable security definer
set search_path = ''
as $$
declare
  v_limit integer := coalesce(p_limit, 500);
  v_rows jsonb;
  v_more boolean;
  v_cursor bigint;
  v_version integer;
  v_result jsonb;
begin
  perform fulla.require_member(p_household_id, 'member');
  if v_limit not between 1 and 1000 then
    perform fulla.invalid('`p_limit` must be from 1 to 1000.');
  end if;

  select coalesce(jsonb_agg(fulla.tx_json(s.t) order by (s.t).server_seq), '[]'::jsonb), max((s.t).server_seq)
    into v_rows, v_cursor
    from (select t from fulla.transactions t
           where t.household_id = p_household_id and t.server_seq > coalesce(p_since, 0)
           order by t.server_seq
           limit v_limit) s;
  v_more := v_cursor is not null and exists (
    select 1 from fulla.transactions t where t.household_id = p_household_id and t.server_seq > v_cursor);

  select h.config_version into v_version from fulla.households h where h.id = p_household_id;
  v_result := jsonb_build_object(
    'transactions', v_rows,
    'cursor', greatest(coalesce(v_cursor, 0), coalesce(p_since, 0)),
    'has_more', v_more,
    'config_version', v_version,
    'server_time', fulla.iso(now()));
  if p_config_version is distinct from v_version then
    v_result := v_result || jsonb_build_object('config', fulla.config_bundle(p_household_id));
  end if;
  return v_result;
end;
$$;

-- ── grants ───────────────────────────────────────────────────────────────────

revoke all on all functions in schema fulla from public, anon, authenticated;

do $$
declare
  f regprocedure;
begin
  for f in select p.oid::regprocedure from pg_proc p
            where p.pronamespace = 'public'::regnamespace and p.proname like 'fulla\_%'
  loop
    execute format('revoke all on function %s from public, anon', f);
    execute format('grant execute on function %s to authenticated', f);
  end loop;
end
$$;
