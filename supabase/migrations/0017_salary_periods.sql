-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- Periods that start when the salary comes in. A household marks one income
-- category as its salary (categories.starts_period); every confirmed income
-- in it starts a period that runs until the day before the next, so a salary
-- paid on the 26th or on the 1st moves the boundary with it, and the latest
-- period stays open until the next salary is written down. The rule is
-- fulla.anchored_period_of, the phone's PeriodRule with anchors; both pass
-- testdata/vectors/period_anchors.json.
--
-- Kept small on purpose: the flag rides on the category, so config_bundle
-- (to_jsonb of each category), fulla_category_upsert (which never names the
-- column, so an edit keeps it) and the config_version trigger on categories
-- carry it with no change. Only the view that sums periods is replaced.

alter table fulla.categories add column starts_period boolean not null default false;

create unique index categories_one_salary on fulla.categories (household_id) where starts_period;

/*
 * The period of a row when salaries decide: the latest salary on or before
 * the date, named after the month it funds (its own before the 15th, the
 * next from then on); only the first salary of each name counts. Before the
 * first salary, fulla.period_of, capped to the month before it.
 */
create function fulla.anchored_period_of(p_date date, p_kind text, p_recurrence text,
                                         p_start_day integer, p_shift_day integer, p_anchors date[]) returns text
language sql immutable parallel safe
as $$
  with a as (
    select min(x.d) as d, x.n
      from (select u.d, to_char(case when extract(day from u.d) >= 15 then u.d + interval '1 month'
                                     else u.d::timestamp end, 'YYYY-MM') as n
              from unnest(coalesce(p_anchors, '{}'::date[])) as u(d)) x
     group by x.n
  )
  select coalesce(
    (select a.n from a where a.d <= p_date order by a.d desc limit 1),
    least(fulla.period_of(p_date, p_kind, p_recurrence, p_start_day, p_shift_day),
          (select to_char(to_date(min(a.n), 'YYYY-MM') - interval '1 month', 'YYYY-MM') from a)));
$$;

-- As in 0008, with each household's salaries. A salary a recurring item
-- wrote on its own counts once somebody has saved it (PeriodAnchors).
create or replace view fulla.period_summary with (security_invoker = true) as
with anchors as (
  select a.household_id, array_agg(a.date) as dates
    from fulla.transactions a
    join fulla.categories c on c.household_id = a.household_id and c.id = a.category_id
   where c.starts_period and not c.archived and a.status = 'active' and a.kind = 'income'
     and (a.recurring_rule_id is null or a.client_updated_at <> a.created_at)
   group by a.household_id
)
select t.household_id,
       fulla.anchored_period_of(t.date, t.kind, t.recurrence, h.period_start_day, h.income_shift_day, an.dates) as period,
       sum(case when t.kind = 'income' then t.amount_minor else 0 end)::bigint as income_minor,
       sum(case t.kind when 'expense' then t.amount_minor when 'refund' then -t.amount_minor else 0 end)::bigint
         as expense_minor,
       sum(case t.kind when 'income' then t.amount_minor when 'expense' then -t.amount_minor
                       when 'refund' then t.amount_minor else 0 end)::bigint as savings_minor
  from fulla.transactions t
  join fulla.households h on h.id = t.household_id
  left join anchors an on an.household_id = t.household_id
 where t.status = 'active' and t.kind in ('income', 'expense', 'refund')
 group by t.household_id, 2;

/*
 * Chooses the household's salary category, or none (null): periods go back
 * to the fixed day. Returns the config bundle.
 */
create function public.fulla_household_set_salary_category(p_household_id uuid, p_category_id uuid) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
begin
  perform fulla.require_member(p_household_id, 'admin');
  if p_category_id is not null and not exists (
    select 1 from fulla.categories c
     where c.household_id = p_household_id and c.id = p_category_id and not c.archived
       and c.applies_to in ('income', 'both')
  ) then
    perform fulla.invalid('The salary must be an income category of this household.');
  end if;
  update fulla.categories c set starts_period = false
   where c.household_id = p_household_id and c.starts_period and c.id is distinct from p_category_id;
  update fulla.categories c set starts_period = true
   where c.household_id = p_household_id and c.id = p_category_id and not c.starts_period;
  return fulla.config_bundle(p_household_id);
end;
$$;

create or replace function public.fulla_schema_version() returns integer
language sql immutable security definer
set search_path = ''
as $$
  select 17;
$$;

-- ── grants ───────────────────────────────────────────────────────────────────

revoke all on function fulla.anchored_period_of(date, text, text, integer, integer, date[]) from public, anon, authenticated;
revoke all on function public.fulla_household_set_salary_category(uuid, uuid) from public, anon;
grant execute on function public.fulla_household_set_salary_category(uuid, uuid) to authenticated;
revoke all on function public.fulla_schema_version() from public, anon;
grant execute on function public.fulla_schema_version() to authenticated;
