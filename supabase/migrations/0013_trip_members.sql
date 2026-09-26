-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- Trip membership: who a trip is for.
--
-- Owner feedback (invented example, matching the rest of this repo): on a
-- solo work trip to Porto, a spouse who is a household member but was never
-- on that trip could still log a personal grocery run into it by accident,
-- because the Add screen's trip chip was buried in the details sheet and
-- picked whichever trip covered the date for anyone. `member_ids` names who
-- a trip is actually for; the app only auto-selects a trip for a new row
-- when the person entering it is one of them (core/.../trips/Trips.kt,
-- `defaultFor`). Everyone can still choose any trip explicitly — this column
-- narrows the *default*, not who is allowed to assign it.
--
-- Same "absent keeps its value" rule as extras (fulla.merge_extras) and
-- trip_id itself (0012_trips.sql): an old app version's upsert (one built
-- before this column existed) omits `member_ids` entirely, and must not
-- silently reset an existing trip back to "nobody" or "creator only". Only
-- an explicit array in the payload changes it. A brand new trip with no key
-- at all defaults to the creator alone, mirroring "assigning a trip is
-- explicit before it can surprise anyone."
--
-- A trip created before this migration has member_ids = '{}' (empty): the
-- creator is unknown, so core.Trips.defaultFor treats that the same as "no
-- auto-select" rather than guessing everyone or no one.

alter table fulla.trips
  add column member_ids uuid[] not null default '{}'::uuid[];

-- ── save_trip: validate member_ids, keep-on-absent, default to the creator ───

-- `create or replace` cannot change a function's argument list; the 2-arg
-- 0012 signature must be dropped first, or both would exist side by side and
-- every 2-arg call (still every call site but fulla_trip_upsert) would keep
-- resolving to the old one, member_ids logic and all.
drop function if exists fulla.save_trip(uuid, jsonb);

create function fulla.save_trip(p_household_id uuid, p jsonb, p_creator_member_id uuid default null) returns uuid
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
  v_member_ids uuid[];
  v_existing fulla.trips;
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

  -- Absent key: keep whatever is stored (or, for a brand new trip, the
  -- creator alone). An explicit key, even `[]`, is validated and applied.
  if p ? 'member_ids' then
    if jsonb_typeof(p -> 'member_ids') <> 'array' then
      perform fulla.invalid('`member_ids` must be an array of member ids.');
    end if;
    select array_agg(distinct fulla.try_uuid(e)) into v_member_ids
      from jsonb_array_elements_text(p -> 'member_ids') e;
    v_member_ids := coalesce(v_member_ids, '{}'::uuid[]);
    if exists (select 1 from unnest(v_member_ids) m where m is null) then
      perform fulla.invalid('`member_ids` must be an array of uuids.');
    end if;
    if exists (
      select 1 from unnest(v_member_ids) m
       where not exists (select 1 from fulla.members x where x.household_id = p_household_id and x.id = m)
    ) then
      perform fulla.invalid('`member_ids` must only name members of this household.');
    end if;
  end if;

  select * into v_existing from fulla.trips t where t.id = v_id;
  if v_existing.id is not null then
    update fulla.trips t
       set name = v_name, start_date = v_start, end_date = v_end, budget_minor = v_budget,
           in_category_budgets = v_in_category, archived = v_archived,
           member_ids = coalesce(v_member_ids, t.member_ids)
     where t.id = v_id;
    return v_id;
  end if;
  insert into fulla.trips (id, household_id, name, start_date, end_date, budget_minor, in_category_budgets, archived, member_ids)
  values (v_id, p_household_id, v_name, v_start, v_end, v_budget, v_in_category, v_archived,
          coalesce(v_member_ids, case when p_creator_member_id is null then '{}'::uuid[] else array[p_creator_member_id] end));
  return v_id;
end;
$$;

-- `fulla_trip_upsert` passes the caller's own member id, so a brand new trip
-- with no `member_ids` key defaults to "just me", not to nobody.
create or replace function public.fulla_trip_upsert(p_household_id uuid, p_trip jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_me fulla.members;
begin
  v_me := fulla.require_member(p_household_id, 'member');
  perform fulla.save_trip(p_household_id, p_trip, v_me.id);
  return fulla.config_bundle(p_household_id);
end;
$$;

-- ── config_bundle: trips carry member_ids too ────────────────────────────────

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

-- ── uploading a phone-only household passes the creator too ─────────────────

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
  -- sync: a row naming a trip must find it already there. A phone-only trip
  -- already carries its own member_ids (or '{}' if made before this
  -- migration shipped), so no creator default is needed here.
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
