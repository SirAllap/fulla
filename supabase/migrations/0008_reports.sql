-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- Totals, computed when asked and never stored.
--
-- The phone computes the same figures from its own copy of the rows (core,
-- analytics and balance). These views exist so that the two can be checked
-- against each other, and so that a household can query its own numbers with
-- plain SQL in its own project.
--
-- Both views are security_invoker: a view that ran with its owner's rights
-- would be a hole in row level security the size of its select list.

/*
 * Income, spending and savings per household and period. Spending is
 * expenses minus refunds. Transfers and settlements are money moving between
 * the household's own pockets and people, not money in or out, so they are
 * left out. Deleted rows never count.
 */
create view fulla.period_summary with (security_invoker = true) as
select t.household_id,
       fulla.period_of(t.date, t.kind, t.recurrence, h.period_start_day, h.income_shift_day) as period,
       sum(case when t.kind = 'income' then t.amount_minor else 0 end)::bigint as income_minor,
       sum(case t.kind when 'expense' then t.amount_minor when 'refund' then -t.amount_minor else 0 end)::bigint
         as expense_minor,
       sum(case t.kind when 'income' then t.amount_minor when 'expense' then -t.amount_minor
                       when 'refund' then t.amount_minor else 0 end)::bigint as savings_minor
  from fulla.transactions t
  join fulla.households h on h.id = t.household_id
 where t.status = 'active' and t.kind in ('income', 'expense', 'refund')
 group by t.household_id, 2;

/*
 * What each member has paid, what their share was, and the difference.
 * [shared rule: testdata/vectors/balance.json]
 *
 *   expense A paid by P, split s     paid(P) += A    share(i) += s_i
 *   refund  A to P, split s          paid(P) -= A    share(i) -= s_i
 *   settlement A from X to Y         paid(X) += A    paid(Y) -= A
 *   income, transfer                 no effect
 *
 * An expense without a split (a household of one) is all the payer's share.
 * Balances in a household always add up to zero.
 */
create view fulla.member_balances with (security_invoker = true) as
with active as (
  select * from fulla.transactions t where t.status = 'active' and t.kind in ('expense', 'refund', 'settlement')
),
effects as (
  select a.household_id, a.paid_by_member_id as member_id,
         case a.kind when 'refund' then -a.amount_minor else a.amount_minor end as paid,
         0::bigint as share
    from active a
  union all
  select a.household_id, a.to_member_id, -a.amount_minor, 0
    from active a where a.kind = 'settlement'
  union all
  select a.household_id, s.key::uuid, 0,
         case a.kind when 'refund' then -(s.value::bigint) else s.value::bigint end
    from active a
    cross join lateral jsonb_each_text(fulla.split_shares(a.amount_minor, a.split)) s
   where a.kind in ('expense', 'refund') and a.split is not null
  union all
  select a.household_id, a.paid_by_member_id, 0,
         case a.kind when 'refund' then -a.amount_minor else a.amount_minor end
    from active a
   where a.kind in ('expense', 'refund') and a.split is null
)
select m.household_id,
       m.id as member_id,
       coalesce(sum(e.paid), 0)::bigint as paid_minor,
       coalesce(sum(e.share), 0)::bigint as share_minor,
       (coalesce(sum(e.paid), 0) - coalesce(sum(e.share), 0))::bigint as balance_minor
  from fulla.members m
  left join effects e on e.household_id = m.household_id and e.member_id = m.id
 group by m.household_id, m.id;

create function public.fulla_period_summary(p_household_id uuid, p_from text, p_to text) returns jsonb
language plpgsql stable security definer
set search_path = ''
as $$
begin
  perform fulla.require_member(p_household_id, 'member');
  if coalesce(p_from, '') !~ '^[0-9]{4}-(0[1-9]|1[0-2])$' or coalesce(p_to, '') !~ '^[0-9]{4}-(0[1-9]|1[0-2])$' then
    perform fulla.invalid('Both periods must be YYYY-MM.');
  end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object('period', s.period, 'income_minor', s.income_minor,
                                        'expense_minor', s.expense_minor, 'savings_minor', s.savings_minor)
                     order by s.period)
      from fulla.period_summary s
     where s.household_id = p_household_id and s.period between p_from and p_to
  ), '[]'::jsonb);
end;
$$;

create function public.fulla_member_balances(p_household_id uuid) returns jsonb
language plpgsql stable security definer
set search_path = ''
as $$
begin
  perform fulla.require_member(p_household_id, 'member');
  return coalesce((
    select jsonb_agg(jsonb_build_object('member_id', b.member_id, 'paid_minor', b.paid_minor,
                                        'share_minor', b.share_minor, 'balance_minor', b.balance_minor)
                     order by b.member_id)
      from fulla.member_balances b
     where b.household_id = p_household_id
  ), '[]'::jsonb);
end;
$$;

-- ── grants ───────────────────────────────────────────────────────────────────

revoke all on all tables in schema fulla from public, anon, authenticated;
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
