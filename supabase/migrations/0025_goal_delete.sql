-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- Deleting a savings goal.
--
-- Example (invented): Alice made "New car" to see how goals look and wants
-- it gone.
--
-- A goal is never physically deleted (fulla.goals_forbid_delete), so deleting
-- one tombstones it, as a trip: `deleted` is set and the config bundle no
-- longer lists it, so no phone shows it again. Archiving stays what it was:
-- hidden from the lists but still there to be brought back.

alter table fulla.goals add column deleted boolean not null default false;

create function public.fulla_goal_delete(p_household_id uuid, p_goal_id uuid) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_me fulla.members := fulla.require_member(p_household_id, 'member');
begin
  update fulla.goals set deleted = true
   where household_id = p_household_id and id = p_goal_id and not deleted;
  if not found then
    perform fulla.fail(404, 'not_found', 'No such goal.');
  end if;
  return fulla.config_bundle(p_household_id);
end;
$$;

-- ── the bundle leaves a deleted goal out [0023_goals.sql unchanged otherwise] ──

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
        from fulla.goals g where g.household_id = h.id and not g.deleted), '[]')
  )
  from fulla.households h
  where h.id = p_household_id;
$$;

create or replace function public.fulla_schema_version() returns integer
language sql immutable security definer
set search_path = ''
as $$
  select 25;
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
