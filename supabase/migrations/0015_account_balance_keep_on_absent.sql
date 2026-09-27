-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- fulla.save_account already keeps opening_balance_date on an absent key
-- (0014_opening_balance_date.sql), but opening_balance_minor never got the
-- same treatment: it went straight through coalesce(..., 0), so an upsert
-- that omits the key -- exactly what the app's "edit only the name" dialog
-- sends, since it builds its payload from a form that does not touch
-- balance -- zeroed a real opening balance instead of leaving it alone.
-- Same "absent keeps its value, explicit null clears it" rule as
-- opening_balance_date and extras (fulla.merge_extras): only a present key
-- changes the stored amount now.

create or replace function fulla.save_account(p_household_id uuid, p jsonb) returns uuid
language plpgsql volatile
as $$
declare
  v_id uuid := fulla.item_id(p_household_id, 'fulla.accounts', p);
  v_name text := btrim(coalesce(p ->> 'name', ''));
  v_type text := coalesce(p ->> 'type', 'other');
  v_archived boolean := fulla.json_bool(p, 'archived', false);
  v_date date;
  v_balance bigint;
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

  if p ? 'opening_balance_minor' then
    v_balance := coalesce(fulla.json_bigint(p, 'opening_balance_minor'), 0);
  else
    select a.opening_balance_minor into v_balance from fulla.accounts a where a.id = v_id;
    v_balance := coalesce(v_balance, 0);
  end if;

  insert into fulla.accounts (id, household_id, name, type, opening_balance_minor, opening_balance_date, sort, archived)
  values (v_id, p_household_id, v_name, v_type, v_balance, v_date,
          fulla.json_int(p, 'sort', 0, -1000000, 1000000), v_archived)
  on conflict (id) do update
     set name = excluded.name, type = excluded.type, opening_balance_minor = excluded.opening_balance_minor,
         opening_balance_date = excluded.opening_balance_date, sort = excluded.sort, archived = excluded.archived;
  return v_id;
end;
$$;

-- ── grants ───────────────────────────────────────────────────────────────────
-- No new public function: fulla_account_upsert (0006_config.sql) is
-- unchanged.
