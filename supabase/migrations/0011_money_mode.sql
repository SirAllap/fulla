-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- How money works between the people of a household.
--
--   split   each expense is shared between people, and balances say who owes
--           whom (what every household did before this migration)
--   shared  one pot: whoever adds an expense, it is all theirs to carry, so it
--           moves no balance; nothing new can be settled
--   null    not chosen yet; behaves like split
--
-- The shared pot rule exists twice, here (fulla.shared_pot_for_new, applied
-- where fulla_sync_push inserts a row) and in core/ (SharedPot.forNew). Both
-- pass testdata/vectors/shared_pot.json. It applies to new rows only: an edit
-- of an older row keeps the split it has, and nothing in the past is
-- rewritten when a household switches. A phone's own new row that meets one
-- already stored under the same id (a recurring occurrence or an imported
-- line two phones both wrote) is still new for that phone and follows the
-- rule too.
--
-- fulla_household_create_from_local does not take money_mode. A household
-- that lived on one phone uploads its history as new rows; were the pot
-- already shared on the server, that history would be rewritten and its
-- settlements refused. The phone sets money_mode with fulla_household_update
-- once its old rows are in.

alter table fulla.households
  add column money_mode text check (money_mode in ('split', 'shared'));

-- ── config_version ───────────────────────────────────────────────────────────

-- As in 0001, with money_mode: every phone must hear that it changed.
create or replace function fulla.bump_household_config_version() returns trigger
language plpgsql
as $$
begin
  if (new.name, new.currency, new.locale, new.period_start_day, new.income_shift_day, new.week_start, new.member_limit,
      new.money_mode)
     is distinct from
     (old.name, old.currency, old.locale, old.period_start_day, old.income_shift_day, old.week_start, old.member_limit,
      old.money_mode)
  then
    new.config_version := old.config_version + 1;
  end if;
  return new;
end;
$$;

-- ── the bundle ───────────────────────────────────────────────────────────────

create or replace function fulla.config_bundle(p_household_id uuid) returns jsonb
language sql stable
as $$
  select jsonb_build_object(
    'config_version', h.config_version,
    'household', jsonb_build_object(
      'id', h.id, 'name', h.name, 'currency', h.currency, 'locale', h.locale,
      'period_start_day', h.period_start_day, 'income_shift_day', h.income_shift_day,
      'week_start', h.week_start, 'member_limit', h.member_limit, 'money_mode', h.money_mode),
    'me_member_id', (select m.id from fulla.members m
                      where m.household_id = h.id and m.user_id = auth.uid() and m.status = 'active'),
    'members', coalesce((
      select jsonb_agg(jsonb_build_object(
               'id', m.id, 'display_name', m.display_name, 'initials', m.initials,
               'color_index', m.color_index, 'role', m.role, 'status', m.status,
               'has_account', m.user_id is not null) order by m.created_at, m.id)
        from fulla.members m where m.household_id = h.id), '[]'),
    'accounts', coalesce((
      select jsonb_agg(to_jsonb(a) - 'household_id' order by a.sort, a.id)
        from fulla.accounts a where a.household_id = h.id), '[]'),
    'categories', coalesce((
      select jsonb_agg(to_jsonb(c) - 'household_id' order by c.sort, c.id)
        from fulla.categories c where c.household_id = h.id), '[]'),
    'custom_fields', coalesce((
      select jsonb_agg(to_jsonb(f) - 'household_id' order by f.sort, f.key)
        from fulla.custom_fields f where f.household_id = h.id), '[]'),
    'budgets', coalesce((
      select jsonb_agg(to_jsonb(b) - 'household_id' order by b.period nulls first, b.category_id)
        from fulla.budgets b where b.household_id = h.id), '[]'),
    'recurring_rules', coalesce((
      select jsonb_agg(to_jsonb(r) - 'household_id' order by r.name, r.id)
        from fulla.recurring_rules r where r.household_id = h.id), '[]'),
    'categorization_rules', coalesce((
      select jsonb_agg(to_jsonb(r) - 'household_id' order by r.sort, r.id)
        from fulla.categorization_rules r where r.household_id = h.id), '[]'),
    'import_profiles', coalesce((
      select jsonb_agg(to_jsonb(p) - 'household_id' order by p.name, p.id)
        from fulla.import_profiles p where p.household_id = h.id), '[]')
  )
  from fulla.households h
  where h.id = p_household_id;
$$;

-- ── the shared pot [shared rule: testdata/vectors/shared_pot.json] ──────────

/*
 * A new transaction as a household with one shared pot stores it; any other
 * household gets it back unchanged. Takes and returns the canonical form
 * fulla.validate_transaction produces.
 *
 *   expense, refund   split between the payer alone: all of it is their own
 *                     share, so it moves no balance. Who paid stays, as
 *                     information.
 *   settlement        refused: in one pot there is nothing to settle.
 *   income, transfer  unchanged; they never moved a balance.
 *
 * Only for rows that do not exist yet. An edit keeps the split it has.
 */
create function fulla.shared_pot_for_new(p_household_id uuid, p_tx jsonb) returns jsonb
language plpgsql stable
as $$
declare
  v_mode text;
begin
  select h.money_mode into v_mode from fulla.households h where h.id = p_household_id;
  if v_mode is distinct from 'shared' then
    return p_tx;
  end if;
  if p_tx ->> 'kind' = 'settlement' then
    perform fulla.invalid('This household shares one pot; there is nothing to settle.');
  end if;
  if p_tx ->> 'kind' in ('expense', 'refund') and p_tx ->> 'paid_by_member_id' is not null then
    return p_tx || jsonb_build_object('split', jsonb_build_object(
      'mode', 'equal', 'members', jsonb_build_array(p_tx ->> 'paid_by_member_id')));
  end if;
  return p_tx;
end;
$$;

-- money_mode as a patch or a payload carries it: absent, null, "split" or "shared".
create function fulla.check_money_mode(p jsonb) returns void
language plpgsql immutable
as $$
begin
  if p is not null and jsonb_typeof(p) <> 'null' and (jsonb_typeof(p) <> 'string' or p #>> '{}' not in ('split', 'shared')) then
    perform fulla.invalid('`money_mode` must be split, shared or null.');
  end if;
end;
$$;

-- ── household settings and sync, as before, knowing about money_mode ─────────

/*
 * Household settings. Admins may change the name, locale, period rules, first
 * day of the week and how money works between members (money_mode); only the owner may change the currency or the member
 * limit. Changing the currency converts nothing: amounts keep their numbers.
 */
create or replace function public.fulla_household_update(p_household_id uuid, p_patch jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_me fulla.members := fulla.require_member(p_household_id, 'admin');
  v_h fulla.households;
begin
  if jsonb_typeof(p_patch) <> 'object' then
    perform fulla.invalid('The patch must be an object.');
  end if;
  if (p_patch ? 'currency' or p_patch ? 'member_limit') and v_me.role <> 'owner' then
    perform fulla.fail(403, 'forbidden_role', 'Only the owner can change the currency or the member limit.');
  end if;
  select * into v_h from fulla.households h where h.id = p_household_id for update;

  if p_patch ? 'name' then v_h.name := btrim(p_patch ->> 'name'); end if;
  if p_patch ? 'currency' then v_h.currency := upper(p_patch ->> 'currency'); end if;
  if p_patch ? 'locale' then v_h.locale := p_patch ->> 'locale'; end if;
  perform fulla.check_household_fields(v_h.name, v_h.currency, v_h.locale);

  if p_patch ? 'period_start_day' then v_h.period_start_day := coalesce((p_patch ->> 'period_start_day')::smallint, 1); end if;
  if p_patch ? 'income_shift_day' then v_h.income_shift_day := (p_patch ->> 'income_shift_day')::smallint; end if;
  if p_patch ? 'week_start' then v_h.week_start := (p_patch ->> 'week_start')::smallint; end if;
  if p_patch ? 'member_limit' then v_h.member_limit := (p_patch ->> 'member_limit')::smallint; end if;
  if p_patch ? 'money_mode' then
    perform fulla.check_money_mode(p_patch -> 'money_mode');
    v_h.money_mode := p_patch ->> 'money_mode';
  end if;

  if v_h.period_start_day not between 1 and 28 then
    perform fulla.invalid('The period must start on a day from 1 to 28.');
  end if;
  if v_h.income_shift_day is not null and v_h.income_shift_day not between 1 and 31 then
    perform fulla.invalid('The income shift day must be from 1 to 31.');
  end if;
  if v_h.period_start_day > 1 and v_h.income_shift_day is not null then
    perform fulla.invalid('A period that starts mid-month and shifted income cannot both be on.');
  end if;
  if v_h.week_start not between 1 and 7 then
    perform fulla.invalid('The week starts on a day from 1 (Monday) to 7 (Sunday).');
  end if;
  if v_h.member_limit not between 1 and 50 or v_h.member_limit < fulla.active_member_count(p_household_id) then
    perform fulla.invalid('The member limit must be 1 to 50 and not below the current number of members.');
  end if;

  update fulla.households h
     set name = v_h.name, currency = v_h.currency, locale = v_h.locale,
         period_start_day = v_h.period_start_day, income_shift_day = v_h.income_shift_day,
         week_start = v_h.week_start, member_limit = v_h.member_limit, money_mode = v_h.money_mode
   where h.id = p_household_id;

  return fulla.config_bundle(p_household_id);
end;
$$;

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
 * refused as older also names the winner in `conflict`. A new row that the
 * shared pot rule changed on its way in (fulla.shared_pot_for_new) comes back
 * as `server_transaction` too, so the phone holds what was stored. New means
 * new to the phone: an upsert without base_client_updated_at that finds a row
 * already stored (two phones generated the same recurring occurrence) counts.
 */
create or replace function public.fulla_sync_push(p_household_id uuid, p_mutations jsonb) returns jsonb
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
  v_stored jsonb;
  v_row fulla.transactions;
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
        v_stored := fulla.shared_pot_for_new(p_household_id, v_new);
        insert into fulla.transactions (
          id, household_id, kind, date, amount_minor, category_id, account_id, to_account_id,
          paid_by_member_id, to_member_id, split, recurrence, note, tags, extras, status,
          recurring_rule_id, occurrence_date, import_fingerprint, original_amount_minor, original_currency,
          created_at, client_updated_at, created_by_member_id, updated_by_user_id
        ) values (
          v_id, p_household_id, v_stored ->> 'kind', (v_stored ->> 'date')::date, (v_stored ->> 'amount_minor')::bigint,
          (v_stored ->> 'category_id')::uuid, (v_stored ->> 'account_id')::uuid, (v_stored ->> 'to_account_id')::uuid,
          (v_stored ->> 'paid_by_member_id')::uuid, (v_stored ->> 'to_member_id')::uuid,
          nullif(v_stored -> 'split', 'null'::jsonb), v_stored ->> 'recurrence', v_stored ->> 'note',
          array(select jsonb_array_elements_text(v_stored -> 'tags')), v_stored -> 'extras', v_stored ->> 'status',
          (v_stored ->> 'recurring_rule_id')::uuid, (v_stored ->> 'occurrence_date')::date, v_stored ->> 'import_fingerprint',
          (v_stored ->> 'original_amount_minor')::bigint, v_stored ->> 'original_currency',
          coalesce((v_tx ->> 'created_at')::timestamptz, v_stamp), v_stamp, v_me.id, auth.uid()
        ) returning * into v_row;
        v_result := jsonb_build_object('ok', true, 'applied', true, 'warnings', v_checked -> 'warnings');
        -- The household shares one pot and the row changed on its way in:
        -- the phone adopts the stored version at once, as for any answer
        -- that carries one.
        if v_stored is distinct from v_new then
          v_result := v_result || jsonb_build_object('server_transaction', fulla.tx_json(v_row));
        end if;

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
          -- Written by a phone that never saw the stored row: new to that phone.
          v_stored := v_new;
          if v_base is null and v_new ->> 'kind' in ('expense', 'refund') then
            v_stored := fulla.shared_pot_for_new(p_household_id, v_new);
          end if;
          update fulla.transactions t
             set kind = v_stored ->> 'kind', date = (v_stored ->> 'date')::date,
                 amount_minor = (v_stored ->> 'amount_minor')::bigint,
                 category_id = (v_stored ->> 'category_id')::uuid, account_id = (v_stored ->> 'account_id')::uuid,
                 to_account_id = (v_stored ->> 'to_account_id')::uuid,
                 paid_by_member_id = (v_stored ->> 'paid_by_member_id')::uuid,
                 to_member_id = (v_stored ->> 'to_member_id')::uuid,
                 split = nullif(v_stored -> 'split', 'null'::jsonb), recurrence = v_stored ->> 'recurrence',
                 note = v_stored ->> 'note', tags = array(select jsonb_array_elements_text(v_stored -> 'tags')),
                 extras = v_stored -> 'extras', status = v_stored ->> 'status',
                 recurring_rule_id = (v_stored ->> 'recurring_rule_id')::uuid,
                 occurrence_date = (v_stored ->> 'occurrence_date')::date,
                 import_fingerprint = v_stored ->> 'import_fingerprint',
                 original_amount_minor = (v_stored ->> 'original_amount_minor')::bigint,
                 original_currency = v_stored ->> 'original_currency',
                 client_updated_at = v_stamp, updated_by_user_id = auth.uid()
           where t.id = v_id
          returning * into v_row;
          v_result := jsonb_build_object('ok', true, 'applied', true, 'warnings', v_checked -> 'warnings');
          if v_stored is distinct from v_new then
            v_result := v_result || jsonb_build_object('server_transaction', fulla.tx_json(v_row));
          end if;
          -- Somebody else's edit landed between this phone's read and its
          -- write. The phone's version wins (it is newer), but it is told.
          if v_mut ? 'base_client_updated_at'
             and (v_base is null or v_base::timestamptz is distinct from v_prior.client_updated_at) then
            v_result := v_result || jsonb_build_object('conflict', jsonb_build_object(
              'winner', 'client',
              'server_updated_at', fulla.iso(v_prior.client_updated_at),
              'client_updated_at', fulla.iso(v_stamp),
              'overwritten', fulla.tx_diff(v_prior_json, v_stored)));
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
