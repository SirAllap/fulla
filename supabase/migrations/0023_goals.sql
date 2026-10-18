-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- Savings goals.
--
-- Example (invented): "Summer 2030", 1.600,00 EUR, kept in the account
-- "Savings", by 2030-07-01.
--
-- A goal is structure, delivered in the config bundle like a trip: a name, an
-- amount to reach, the account the money is kept in and, optionally, a date.
-- What has been saved is that account's balance, worked out on the phone
-- (core/.../goals/Goals.kt) from the transactions everybody already syncs:
-- nothing about progress is stored here, so nothing can disagree with it.
-- Nothing is ever physically deleted: a goal that is done with is archived.

create table fulla.goals (
  id uuid primary key,
  household_id uuid not null references fulla.households (id),
  name text not null check (char_length(name) between 1 and 40),
  target_minor bigint not null check (target_minor > 0),
  account_id uuid not null,
  target_date date,
  archived boolean not null default false,
  created_at timestamptz not null default now(),
  unique (household_id, id),
  foreign key (household_id, account_id) references fulla.accounts (household_id, id)
);

-- ── access control, as every other structure table ──────────────────────────

alter table fulla.goals enable row level security;

create policy goals_members_read on fulla.goals
  for select to authenticated using (fulla.is_active_member(household_id));

create trigger goals_forbid_delete
  before delete on fulla.goals
  for each row execute function fulla.forbid_delete();

create trigger goals_bump_config
  after insert or update on fulla.goals
  for each row execute function fulla.bump_config_version();

-- ── saving a goal ────────────────────────────────────────────────────────────

create function fulla.save_goal(p_household_id uuid, p jsonb) returns uuid
language plpgsql volatile
as $$
declare
  v_id uuid := fulla.item_id(p_household_id, 'fulla.goals', p);
  v_name text := btrim(coalesce(p ->> 'name', ''));
  v_target bigint := fulla.json_bigint(p, 'target_minor');
  v_account uuid := fulla.json_uuid(p, 'account_id');
  v_date date := null;
  v_archived boolean := fulla.json_bool(p, 'archived', false);
begin
  if char_length(v_name) not between 1 and 40 then
    perform fulla.invalid('A goal name must be 1 to 40 characters.');
  end if;
  if v_target is null or v_target <= 0 then
    perform fulla.invalid('`target_minor` must be a whole number greater than zero.');
  end if;
  if v_account is null or not exists (
       select 1 from fulla.accounts a where a.id = v_account and a.household_id = p_household_id) then
    perform fulla.invalid('`account_id` must be an account of this household.');
  end if;
  if p ? 'target_date' and jsonb_typeof(p -> 'target_date') <> 'null' then
    v_date := fulla.try_date(p ->> 'target_date');
    if v_date is null then
      perform fulla.invalid('`target_date` must be a date (YYYY-MM-DD), or null for no date.');
    end if;
  end if;

  insert into fulla.goals (id, household_id, name, target_minor, account_id, target_date, archived)
  values (v_id, p_household_id, v_name, v_target, v_account, v_date, v_archived)
  on conflict (id) do update
     set name = excluded.name, target_minor = excluded.target_minor, account_id = excluded.account_id,
         target_date = excluded.target_date, archived = excluded.archived;
  return v_id;
end;
$$;

/*
 * A goal needs a connection like any other structure, and the `member` role,
 * as a trip: saving up is the household's, not only its admins'.
 */
create function public.fulla_goal_upsert(p_household_id uuid, p_goal jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
begin
  perform fulla.require_member(p_household_id, 'member');
  perform fulla.save_goal(p_household_id, p_goal);
  return fulla.config_bundle(p_household_id);
end;
$$;

-- ── the bundle, knowing about goals [0013_trip_members.sql unchanged otherwise] ─

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
    -- A deleted trip is a tombstone in storage only (docs/CLAUDE.md: nothing
    -- is ever physically deleted), never in the bundle: unlike a synced row,
    -- a trip is delivered as a full snapshot on every config change, so
    -- there is no cursor a phone could miss it on by leaving it out, and
    -- every screen that lists trips (Settings, Overview, the Add toggle,
    -- Trips.defaultFor) simply never hears about it again.
    'trips', coalesce((
      select jsonb_agg(to_jsonb(t) - 'household_id' order by t.start_date desc, t.id)
        from fulla.trips t where t.household_id = h.id and t.status = 'active'), '[]'),
    'goals', coalesce((
      select jsonb_agg(to_jsonb(g) - 'household_id' order by g.created_at, g.id)
        from fulla.goals g where g.household_id = h.id), '[]')
  )
  from fulla.households h
  where h.id = p_household_id;
$$;

-- ── erasing a household deletes its goals too, before its accounts ──────────

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
  delete from fulla.goals where household_id = p_household_id;
  delete from fulla.accounts where household_id = p_household_id;
  delete from fulla.members where household_id = p_household_id;
  delete from fulla.households where id = p_household_id;
  perform set_config('fulla.erasing_household', '', true);
end;
$$;

create or replace function public.fulla_schema_version() returns integer
language sql immutable security definer
set search_path = ''
as $$
  select 23;
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
