-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- Trips and events with a budget.
--
-- Example (invented): "Porto", 2030-08-12 to 2030-08-19, budget 300.00 EUR,
-- household of Alice and Bob.
--
-- A trip is structure, delivered in the config bundle, like a category or an
-- account: it changes rarely and only with a connection. Every transaction
-- may point at one through a nullable trip_id, which follows the same
-- "absent keeps its value" rule as extras (fulla.merge_extras), because an
-- old app version's own row is re-encoded without ever having heard of the
-- column (RoomSyncStore.kt stores rows re-encoded, not the server's raw
-- JSON): its own later edits must not silently wipe a trip another phone
-- set.
--
--   server   fulla_sync_push: absent keeps fulla.transactions.trip_id, an
--            explicit null clears it, an explicit uuid is checked and set
--            (fulla.trip_for).
--   phone    Transaction.tripId/tripKnown (core), Wire writes the key only
--            when tripKnown or tripId is not null (client).
--
-- Overlapping trips are allowed on purpose: a July hotel deposit paid before
-- an August trip both cover, and a household that double-books on purpose
-- (a weekend inside a longer trip) should not be refused. fulla.trip_for
-- picks none of that; a row simply names the trip it belongs to.
--
-- No SQL trip report view in v1: totals live only on the phone
-- (core/.../trips/Trips.kt), so no rule here exists twice and there is no
-- testdata/vectors/trip.json. Add one only together with such a view.

create table fulla.trips (
  id uuid primary key,
  household_id uuid not null references fulla.households (id),
  name text not null check (char_length(name) between 1 and 40),
  start_date date not null,
  end_date date not null,
  budget_minor bigint check (budget_minor > 0), -- null = track only, no jar
  in_category_budgets boolean not null default false,
  archived boolean not null default false,
  created_at timestamptz not null default now(),
  unique (household_id, id),
  check (end_date >= start_date and end_date - start_date < 366)
);

alter table fulla.transactions
  add column trip_id uuid,
  add foreign key (household_id, trip_id) references fulla.trips (household_id, id);

create index transactions_trip on fulla.transactions (household_id, trip_id) where trip_id is not null;

-- ── access control, as every other structure table ──────────────────────────

alter table fulla.trips enable row level security;

create policy trips_members_read on fulla.trips
  for select to authenticated using (fulla.is_active_member(household_id));

create trigger trips_forbid_delete
  before delete on fulla.trips
  for each row execute function fulla.forbid_delete();

create trigger trips_bump_config
  after insert or update on fulla.trips
  for each row execute function fulla.bump_config_version();

-- ── saving a trip ────────────────────────────────────────────────────────────

create function fulla.save_trip(p_household_id uuid, p jsonb) returns uuid
language plpgsql volatile
as $$
declare
  v_id uuid := fulla.item_id(p_household_id, 'fulla.trips', p);
  v_name text := btrim(coalesce(p ->> 'name', ''));
  v_start date := fulla.try_date(p ->> 'start_date');
  v_end date := fulla.try_date(p ->> 'end_date');
  v_budget bigint := fulla.json_bigint(p, 'budget_minor');
  v_in_category boolean := fulla.json_bool(p, 'in_category_budgets', false);
  v_archived boolean := fulla.json_bool(p, 'archived', false);
begin
  if char_length(v_name) not between 1 and 40 then
    perform fulla.invalid('A trip name must be 1 to 40 characters.');
  end if;
  if v_start is null or v_end is null then
    perform fulla.invalid('`start_date` and `end_date` must be dates (YYYY-MM-DD).');
  end if;
  if v_end < v_start or v_end - v_start >= 366 then
    perform fulla.invalid('A trip must end on or after it starts, and last less than a year.');
  end if;
  if v_budget is not null and v_budget <= 0 then
    perform fulla.invalid('`budget_minor` must be a whole number greater than zero, or null to only track spending.');
  end if;

  if exists (select 1 from fulla.trips t where t.id = v_id) then
    update fulla.trips t
       set name = v_name, start_date = v_start, end_date = v_end, budget_minor = v_budget,
           in_category_budgets = v_in_category, archived = v_archived
     where t.id = v_id;
    return v_id;
  end if;
  insert into fulla.trips (id, household_id, name, start_date, end_date, budget_minor, in_category_budgets, archived)
  values (v_id, p_household_id, v_name, v_start, v_end, v_budget, v_in_category, v_archived);
  return v_id;
end;
$$;

/*
 * A trip needs a connection like any other structure (ADR 0010), but the
 * `member` role, not `admin`: in a couple the second person may be a plain
 * member, and a trip is closer to spending than to household configuration.
 */
create function public.fulla_trip_upsert(p_household_id uuid, p_trip jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
begin
  perform fulla.require_member(p_household_id, 'member');
  perform fulla.save_trip(p_household_id, p_trip);
  return fulla.config_bundle(p_household_id);
end;
$$;

/*
 * The trip a transaction's row should be stored with, given the mutation's
 * own JSON [p_tx], the kind it ends up with after every other rule has run
 * [p_kind], and the trip the stored row already carried, if any [p_prior_trip].
 *
 *   absent trip_id, kind expense/refund   keeps p_prior_trip (merge, not
 *                                         replace: the same rule as extras)
 *   explicit null                        clears it
 *   explicit uuid                        checked against this household's
 *                                         trips (archived and out-of-range
 *                                         dates both accepted) and kept
 *   any other kind                       null; an explicit uuid on a kind
 *                                         that cannot have a trip fails, but
 *                                         an absent key becomes null in
 *                                         silence, so a phone that changes a
 *                                         tripped row's kind without ever
 *                                         having heard of trip_id is never
 *                                         rejected for it
 */
create function fulla.trip_for(p_household_id uuid, p_tx jsonb, p_kind text, p_prior_trip uuid) returns uuid
language plpgsql stable
as $$
declare
  v_trip uuid;
begin
  if p_kind not in ('expense', 'refund') then
    if p_tx ? 'trip_id' and (p_tx ->> 'trip_id') is not null then
      perform fulla.invalid(format('A %s has no trip.', p_kind));
    end if;
    return null;
  end if;
  if not (p_tx ? 'trip_id') then
    return p_prior_trip;
  end if;
  v_trip := fulla.json_uuid(p_tx, 'trip_id');
  if v_trip is null then
    return null;
  end if;
  if not exists (select 1 from fulla.trips t where t.household_id = p_household_id and t.id = v_trip) then
    perform fulla.invalid('That trip does not exist in this household.');
  end if;
  return v_trip;
end;
$$;

-- ── the bundle, knowing about trips [0011_money_mode.sql:50 unchanged otherwise] ─

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
        from fulla.import_profiles p where p.household_id = h.id), '[]'),
    'trips', coalesce((
      select jsonb_agg(to_jsonb(t) - 'household_id' order by t.start_date desc, t.id)
        from fulla.trips t where t.household_id = h.id), '[]')
  )
  from fulla.households h
  where h.id = p_household_id;
$$;

-- ── erasing a household deletes trips too, after transactions ───────────────

create or replace function fulla.erase_household(p_household_id uuid) returns void
language plpgsql volatile
as $$
begin
  perform set_config('fulla.erasing_household', p_household_id::text, true);
  delete from fulla.transactions where household_id = p_household_id;
  delete from fulla.trips where household_id = p_household_id;
  delete from fulla.invites where household_id = p_household_id;
  delete from fulla.budgets where household_id = p_household_id;
  delete from fulla.recurring_rules where household_id = p_household_id;
  delete from fulla.categorization_rules where household_id = p_household_id;
  delete from fulla.import_profiles where household_id = p_household_id;
  delete from fulla.custom_fields where household_id = p_household_id;
  update fulla.categories set parent_id = null where household_id = p_household_id and parent_id is not null;
  delete from fulla.categories where household_id = p_household_id;
  delete from fulla.accounts where household_id = p_household_id;
  delete from fulla.members where household_id = p_household_id;
  delete from fulla.households where id = p_household_id;
  perform set_config('fulla.erasing_household', '', true);
end;
$$;

-- ── uploading a phone-only household saves its trips too ────────────────────

create or replace function public.fulla_household_create_from_local(p_payload jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_uid uuid := fulla.require_user();
  v_h jsonb := p_payload -> 'household';
  v_household uuid := fulla.json_uuid(p_payload -> 'household', 'id');
  v_me uuid := fulla.json_uuid(p_payload, 'me_member_id');
  v_existing fulla.members;
  x jsonb;
begin
  if v_household is null or v_me is null then
    perform fulla.invalid('`household.id` and `me_member_id` are required.');
  end if;

  if exists (select 1 from fulla.households h where h.id = v_household) then
    select * into v_existing from fulla.members m
     where m.household_id = v_household and m.user_id = v_uid and m.status = 'active';
    if v_existing.id is null then
      perform fulla.fail(409, 'already_exists', 'A household with this id already exists.');
    end if;
    return jsonb_build_object('household_id', v_household, 'member_id', v_existing.id,
                              'config', fulla.config_bundle(v_household));
  end if;

  perform fulla.check_household_fields(v_h ->> 'name', v_h ->> 'currency', v_h ->> 'locale');
  insert into fulla.households (id, name, currency, locale, period_start_day, income_shift_day, week_start, created_by)
  values (v_household, btrim(v_h ->> 'name'), upper(v_h ->> 'currency'), v_h ->> 'locale',
          coalesce((v_h ->> 'period_start_day')::smallint, 1),
          (v_h ->> 'income_shift_day')::smallint,
          coalesce((v_h ->> 'week_start')::smallint, 1),
          v_uid);

  if not exists (select 1 from jsonb_array_elements(coalesce(p_payload -> 'members', '[]')) m
                  where fulla.try_uuid(m ->> 'id') = v_me) then
    perform fulla.invalid('`me_member_id` must be one of `members`.');
  end if;
  for x in select value from jsonb_array_elements(coalesce(p_payload -> 'members', '[]')) loop
    perform fulla.check_person(x ->> 'display_name', x ->> 'initials', coalesce((x ->> 'color_index')::integer, 0));
    insert into fulla.members (id, household_id, display_name, initials, color_index, status)
    values (fulla.json_uuid(x, 'id'), v_household, btrim(x ->> 'display_name'), btrim(x ->> 'initials'),
            coalesce((x ->> 'color_index')::smallint, 0),
            case when x ->> 'status' = 'archived' then 'archived' else 'active' end);
  end loop;
  update fulla.members
     set user_id = v_uid, role = 'owner', status = 'active', joined_at = now()
   where household_id = v_household and id = v_me;

  if fulla.active_member_count(v_household) > (select h.member_limit from fulla.households h where h.id = v_household) then
    perform fulla.fail(409, 'household_full', 'More members than the household allows.');
  end if;

  for x in select value from jsonb_array_elements(coalesce(p_payload -> 'accounts', '[]')) loop
    perform fulla.save_account(v_household, x);
  end loop;
  -- Parents before children.
  for x in select value from jsonb_array_elements(coalesce(p_payload -> 'categories', '[]'))
            order by (value ->> 'parent_id') is not null loop
    perform fulla.save_category(v_household, x);
  end loop;
  for x in select value from jsonb_array_elements(coalesce(p_payload -> 'custom_fields', '[]')) loop
    perform fulla.save_field(v_household, x);
  end loop;
  for x in select value from jsonb_array_elements(coalesce(p_payload -> 'budgets', '[]')) loop
    perform fulla.save_budget(v_household, x);
  end loop;
  -- Trips, before the phone's transactions are pushed through the ordinary
  -- sync: a row naming a trip must find it already there.
  for x in select value from jsonb_array_elements(coalesce(p_payload -> 'trips', '[]')) loop
    perform fulla.save_trip(v_household, x);
  end loop;
  for x in select value from jsonb_array_elements(coalesce(p_payload -> 'recurring_rules', '[]')) loop
    perform fulla.save_recurring(v_household, x);
  end loop;
  for x in select value from jsonb_array_elements(coalesce(p_payload -> 'categorization_rules', '[]')) loop
    perform fulla.save_rule(v_household, x);
  end loop;
  for x in select value from jsonb_array_elements(coalesce(p_payload -> 'import_profiles', '[]')) loop
    perform fulla.save_import_profile(v_household, x);
  end loop;

  return jsonb_build_object('household_id', v_household, 'member_id', v_me,
                            'config', fulla.config_bundle(v_household));
end;
$$;

-- ── conflict notes name a changed trip too ───────────────────────────────────

create or replace function fulla.tx_diff(p_before jsonb, p_after jsonb) returns jsonb
language sql immutable
as $$
  with fields as (
    select k from unnest(array[
      'kind', 'date', 'amount_minor', 'category_id', 'account_id', 'to_account_id', 'paid_by_member_id',
      'to_member_id', 'split', 'recurrence', 'note', 'tags', 'status', 'recurring_rule_id',
      'occurrence_date', 'import_fingerprint', 'original_amount_minor', 'original_currency', 'trip_id']) k
  ),
  core as (
    -- A missing key (trip_id absent from a candidate that never touched it)
    -- and an explicit JSON null must compare equal here: fulla_sync_push
    -- itself does treat them differently (absent keeps the stored value),
    -- but reporting a "changed" trip_id when nothing about it actually
    -- changed would be a false conflict note.
    select k as field, p_before -> k as before, p_after -> k as after
      from fields
     where coalesce(p_before -> k, 'null'::jsonb) is distinct from coalesce(p_after -> k, 'null'::jsonb)
  ),
  extra_keys as (
    select jsonb_object_keys(coalesce(p_before -> 'extras', '{}')) as k
    union
    select jsonb_object_keys(coalesce(p_after -> 'extras', '{}'))
  ),
  extras as (
    select 'extras.' || k as field, p_before -> 'extras' -> k as before, p_after -> 'extras' -> k as after
      from extra_keys
     where (p_before -> 'extras' -> k) is distinct from (p_after -> 'extras' -> k)
  )
  select coalesce(jsonb_agg(jsonb_build_object('field', field, 'before', before, 'after', after) order by field), '[]')
    from (select * from core union all select * from extras) d;
$$;

-- ── fulla_sync_push, the 0011 version exactly [diff it against 0011_money_mode.sql:222
--    on review: the shared-pot rule below is byte-for-byte the same], plus trip_id ──

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
  v_trip uuid;
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
        v_trip := fulla.trip_for(p_household_id, v_tx, v_stored ->> 'kind', null);
        insert into fulla.transactions (
          id, household_id, kind, date, amount_minor, category_id, account_id, to_account_id,
          paid_by_member_id, to_member_id, split, recurrence, note, tags, extras, status,
          recurring_rule_id, occurrence_date, import_fingerprint, original_amount_minor, original_currency,
          trip_id, created_at, client_updated_at, created_by_member_id, updated_by_user_id
        ) values (
          v_id, p_household_id, v_stored ->> 'kind', (v_stored ->> 'date')::date, (v_stored ->> 'amount_minor')::bigint,
          (v_stored ->> 'category_id')::uuid, (v_stored ->> 'account_id')::uuid, (v_stored ->> 'to_account_id')::uuid,
          (v_stored ->> 'paid_by_member_id')::uuid, (v_stored ->> 'to_member_id')::uuid,
          nullif(v_stored -> 'split', 'null'::jsonb), v_stored ->> 'recurrence', v_stored ->> 'note',
          array(select jsonb_array_elements_text(v_stored -> 'tags')), v_stored -> 'extras', v_stored ->> 'status',
          (v_stored ->> 'recurring_rule_id')::uuid, (v_stored ->> 'occurrence_date')::date, v_stored ->> 'import_fingerprint',
          (v_stored ->> 'original_amount_minor')::bigint, v_stored ->> 'original_currency',
          v_trip, coalesce((v_tx ->> 'created_at')::timestamptz, v_stamp), v_stamp, v_me.id, auth.uid()
        ) returning * into v_row;
        v_result := jsonb_build_object('ok', true, 'applied', true, 'warnings', v_checked -> 'warnings');
        -- The household shares one pot and the row changed on its way in, or
        -- the trip is not what the phone sent (an absent key kept a trip it
        -- does not know about): the phone adopts the stored version at once,
        -- as for any answer that carries one.
        if v_stored is distinct from v_new or v_trip is distinct from fulla.try_uuid(v_tx ->> 'trip_id') then
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
              -- validate_transaction's output never carries a trip_id key (0003:640-661), so
              -- v_new is given the trip this mutation would have stored it with (absent key
              -- keeps the prior trip, same as trip_for, without trip_for's own validation:
              -- nothing here is actually being applied) so the diff doesn't report a false
              -- "trip removed" or "trip added" on every collision on a tripped row.
              'overwritten', fulla.tx_diff(
                v_new || jsonb_build_object('trip_id',
                  case when v_tx ? 'trip_id' then v_tx -> 'trip_id' else to_jsonb(v_prior.trip_id) end),
                v_prior_json)));
        else
          -- Written by a phone that never saw the stored row: new to that phone.
          v_stored := v_new;
          if v_base is null and v_new ->> 'kind' in ('expense', 'refund') then
            v_stored := fulla.shared_pot_for_new(p_household_id, v_new);
          end if;
          v_trip := fulla.trip_for(p_household_id, v_tx, v_stored ->> 'kind', v_prior.trip_id);
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
                 trip_id = v_trip,
                 client_updated_at = v_stamp, updated_by_user_id = auth.uid()
           where t.id = v_id
          returning * into v_row;
          v_result := jsonb_build_object('ok', true, 'applied', true, 'warnings', v_checked -> 'warnings');
          if v_stored is distinct from v_new or v_trip is distinct from fulla.try_uuid(v_tx ->> 'trip_id') then
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
              -- v_stored never carries trip_id either (it's built from v_new); v_trip, computed
              -- above, is what actually won, so the diff reports it instead of a false note.
              'overwritten', fulla.tx_diff(v_prior_json, v_stored || jsonb_build_object('trip_id', to_jsonb(v_trip)))));
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
