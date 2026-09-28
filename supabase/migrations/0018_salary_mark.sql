-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- The salary that starts a period is marked on the income itself, not
-- chosen by category (0017): a household with two salaries in one category
-- could not say which one moves the month. The mark is the reserved tag
-- `fulla:starts-period`, so it syncs like any tag and fulla_sync_push needs
-- no change; the phone hides it (PeriodAnchors). The rule itself,
-- fulla.anchored_period_of, is unchanged.

-- As in 0017, with the marked incomes instead of a salary category.
create or replace view fulla.period_summary with (security_invoker = true) as
with anchors as (
  select a.household_id, array_agg(a.date) as dates
    from fulla.transactions a
   where a.status = 'active' and a.kind = 'income' and 'fulla:starts-period' = any (a.tags)
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

drop function public.fulla_household_set_salary_category(uuid, uuid);
drop index fulla.categories_one_salary;
alter table fulla.categories drop column starts_period;

create or replace function public.fulla_schema_version() returns integer
language sql immutable security definer
set search_path = ''
as $$
  select 18;
$$;

-- ── grants ───────────────────────────────────────────────────────────────────

revoke all on function public.fulla_schema_version() from public, anon;
grant execute on function public.fulla_schema_version() to authenticated;
