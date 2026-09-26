-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- An account's opening balance had no date, so someone entering today's bank
-- balance and then backfilling past expenses saw those expenses subtracted
-- twice: once already folded into the balance they typed, once again as
-- movements. `opening_balance_date` names the day the balance was measured,
-- as of its start; a movement dated on or after it is added on top, a
-- movement dated before it is already accounted for and does not count
-- again. Null (a legacy account, or one nobody has touched since this
-- shipped) keeps the old behaviour: every movement counts.
--
-- Same "absent keeps its value" rule as extras (fulla.merge_extras) and
-- trip_id/member_ids (0012_trips.sql, 0013_trip_members.sql): an old app
-- version's upsert omits `opening_balance_date` entirely, and must not
-- silently clear a date another phone set. Only an explicit key, including
-- an explicit null, changes it.
--
-- The balance itself is computed only on the phone (core's
-- Analytics.accountBalances); no SQL function computes a current balance, so
-- there is nothing else here to keep in step with it.

alter table fulla.accounts
  add column opening_balance_date date;

-- ── save_account: validate, keep-on-absent, clear-on-null [0006_config.sql] ──

create or replace function fulla.save_account(p_household_id uuid, p jsonb) returns uuid
language plpgsql volatile
as $$
declare
  v_id uuid := fulla.item_id(p_household_id, 'fulla.accounts', p);
  v_name text := btrim(coalesce(p ->> 'name', ''));
  v_type text := coalesce(p ->> 'type', 'other');
  v_archived boolean := fulla.json_bool(p, 'archived', false);
  v_date date;
begin
  if char_length(v_name) not between 1 and 40 then
    perform fulla.invalid('An account name must be 1 to 40 characters.');
  end if;
  if v_type not in ('cash', 'checking', 'savings', 'credit_card', 'other') then
    perform fulla.invalid('`type` must be cash, checking, savings, credit_card or other.');
  end if;
  if not v_archived and exists (
    select 1 from fulla.accounts a
     where a.household_id = p_household_id and a.id <> v_id and not a.archived
       and fulla.normalize_name(a.name) = fulla.normalize_name(v_name)
  ) then
    perform fulla.invalid(format('There is already an account called «%s».', v_name));
  end if;

  if p ? 'opening_balance_date' then
    if jsonb_typeof(p -> 'opening_balance_date') <> 'null' then
      v_date := fulla.try_date(p ->> 'opening_balance_date');
      if v_date is null then
        perform fulla.invalid('`opening_balance_date` must be a date (YYYY-MM-DD).');
      end if;
    end if;
  else
    select a.opening_balance_date into v_date from fulla.accounts a where a.id = v_id;
  end if;

  insert into fulla.accounts (id, household_id, name, type, opening_balance_minor, opening_balance_date, sort, archived)
  values (v_id, p_household_id, v_name, v_type, coalesce(fulla.json_bigint(p, 'opening_balance_minor'), 0), v_date,
          fulla.json_int(p, 'sort', 0, -1000000, 1000000), v_archived)
  on conflict (id) do update
     set name = excluded.name, type = excluded.type, opening_balance_minor = excluded.opening_balance_minor,
         opening_balance_date = excluded.opening_balance_date, sort = excluded.sort, archived = excluded.archived;
  return v_id;
end;
$$;

-- ── grants ───────────────────────────────────────────────────────────────────
-- No new public function: fulla_account_upsert (0006_config.sql) is
-- unchanged and already grants only to authenticated. config_bundle
-- (0006_config.sql) already carries every column of fulla.accounts through
-- to_jsonb(a), so the pull and fulla_household_create_from_local (which
-- calls fulla.save_account for each account) need no changes of their own.
