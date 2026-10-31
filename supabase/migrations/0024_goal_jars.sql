-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- A savings goal with a jar of its own.
--
-- Example (invented): "New car", 9.000,00 EUR, no account; Alice puts in
-- 200,00 on 2030-01-05, Bob 150,00 on 2030-01-20, and Alice takes 50,00 back
-- out on 2030-02-02 (a negative amount).
--
-- Until now what a goal had saved was the balance of one account: a goal
-- had to name an account, which made no sense for money kept in the everyday
-- account. A goal may now keep its own jar instead: what is put in and taken
-- out, one entry each, in `contributions`. A goal that still names an
-- account goes on reading that account's balance (core/.../goals/Goals.kt).
--
-- Entries are merged by id, never replaced as a whole: a phone sends the
-- goal with the entries it knows, and the server keeps every entry it already
-- had that the phone did not send. Two phones putting money in at the same
-- time therefore both count, and saving the goal again from a phone that has
-- not heard of an entry yet never loses it. An entry is taken back by sending
-- it again, same id, with `deleted` true (nothing is ever physically deleted).

alter table fulla.goals add column contributions jsonb not null default '[]'::jsonb;
alter table fulla.goals alter column account_id drop not null;

create or replace function fulla.save_goal(p_household_id uuid, p jsonb) returns uuid
language plpgsql volatile
as $$
declare
  v_id uuid := fulla.item_id(p_household_id, 'fulla.goals', p);
  v_name text := btrim(coalesce(p ->> 'name', ''));
  v_target bigint := fulla.json_bigint(p, 'target_minor');
  v_account uuid := fulla.json_uuid(p, 'account_id');
  v_date date := null;
  v_archived boolean := fulla.json_bool(p, 'archived', false);
  v_incoming jsonb := coalesce(p -> 'contributions', '[]'::jsonb);
  v_entry jsonb;
  v_merged jsonb;
begin
  if char_length(v_name) not between 1 and 40 then
    perform fulla.invalid('A goal name must be 1 to 40 characters.');
  end if;
  if v_target is null or v_target <= 0 then
    perform fulla.invalid('`target_minor` must be a whole number greater than zero.');
  end if;
  -- No account: the goal keeps its own jar. An account, when named, must be the household's.
  if v_account is not null and not exists (
       select 1 from fulla.accounts a where a.id = v_account and a.household_id = p_household_id) then
    perform fulla.invalid('`account_id` must be an account of this household, or null for a jar of its own.');
  end if;
  if p ? 'account_id' and jsonb_typeof(p -> 'account_id') <> 'null' and v_account is null then
    perform fulla.invalid('`account_id` must be an account of this household, or null for a jar of its own.');
  end if;
  if p ? 'target_date' and jsonb_typeof(p -> 'target_date') <> 'null' then
    v_date := fulla.try_date(p ->> 'target_date');
    if v_date is null then
      perform fulla.invalid('`target_date` must be a date (YYYY-MM-DD), or null for no date.');
    end if;
  end if;

  if jsonb_typeof(v_incoming) <> 'array' or jsonb_array_length(v_incoming) > 5000 then
    perform fulla.invalid('`contributions` must be a list of at most 5000 entries.');
  end if;
  for v_entry in select e from jsonb_array_elements(v_incoming) e loop
    if jsonb_typeof(v_entry) <> 'object'
       or fulla.json_uuid(v_entry, 'id') is null
       or fulla.try_date(v_entry ->> 'date') is null
       or fulla.json_bigint(v_entry, 'amount_minor') is null
       or fulla.json_bigint(v_entry, 'amount_minor') = 0
       or abs(fulla.json_bigint(v_entry, 'amount_minor')) > 100000000000
       or char_length(coalesce(v_entry ->> 'note', '')) > 80 then
      perform fulla.invalid('Each entry in `contributions` needs an `id`, a `date` and a non-zero whole `amount_minor`; a `note` is at most 80 characters.');
    end if;
  end loop;

  -- What the phone sent wins for its own ids; every other entry already stored stays.
  select coalesce(jsonb_agg(e order by e ->> 'date', e ->> 'id'), '[]'::jsonb) into v_merged
    from (
      select distinct on (s.e ->> 'id') s.e
        from (
          select jsonb_build_object(
                   'id', x ->> 'id', 'date', x ->> 'date', 'amount_minor', fulla.json_bigint(x, 'amount_minor'),
                   'note', nullif(btrim(coalesce(x ->> 'note', '')), ''), 'member_id', x ->> 'member_id',
                   'deleted', fulla.json_bool(x, 'deleted', false)) as e, 1 as pri
            from jsonb_array_elements(v_incoming) x
          union all
          select x, 2 from fulla.goals g, jsonb_array_elements(g.contributions) x
           where g.id = v_id and g.household_id = p_household_id
        ) s
       order by s.e ->> 'id', s.pri
    ) merged;
  if jsonb_array_length(v_merged) > 5000 then
    perform fulla.invalid('A goal holds at most 5000 entries.');
  end if;

  insert into fulla.goals (id, household_id, name, target_minor, account_id, target_date, archived, contributions)
  values (v_id, p_household_id, v_name, v_target, v_account, v_date, v_archived, v_merged)
  on conflict (id) do update
     set name = excluded.name, target_minor = excluded.target_minor, account_id = excluded.account_id,
         target_date = excluded.target_date, archived = excluded.archived, contributions = excluded.contributions;
  return v_id;
end;
$$;

create or replace function public.fulla_schema_version() returns integer
language sql immutable security definer
set search_path = ''
as $$
  select 24;
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
