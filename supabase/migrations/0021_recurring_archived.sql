-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- A recurring item can be deleted from the app. Nothing is physically
-- deleted here (rows it wrote point at it, and every table refuses DELETE), so
-- deleting is archiving, as it is for accounts and categories: `archived`
-- hides the item and it never writes again. What it already wrote stays.
--
-- The column also arrives in the household's config (it is built from
-- to_jsonb of the row), and an item saved without it is not archived.

alter table fulla.recurring_rules add column archived boolean not null default false;

create or replace function fulla.save_recurring(p_household_id uuid, p jsonb) returns uuid
language plpgsql volatile
as $$
declare
  v_id uuid := fulla.item_id(p_household_id, 'fulla.recurring_rules', p);
  v_name text := btrim(coalesce(p ->> 'name', ''));
  v_start date := fulla.try_date(p ->> 'start_date');
  v_end date := fulla.try_date(p ->> 'end_date');
  v_template jsonb;
begin
  if char_length(v_name) not between 1 and 60 then
    perform fulla.invalid('A recurring item''s name must be 1 to 60 characters.');
  end if;
  if v_start is null then
    perform fulla.invalid('`start_date` is required.');
  end if;
  if p ->> 'end_date' is not null and (v_end is null or v_end < v_start) then
    perform fulla.invalid('`end_date` must be a date on or after `start_date`.');
  end if;
  if jsonb_typeof(p -> 'template') <> 'object' then
    perform fulla.invalid('`template` must be a transaction without id, date or status.');
  end if;
  -- A template is checked exactly as a transaction would be, then stripped of
  -- what each occurrence supplies for itself.
  v_template := fulla.validate_transaction(
    p_household_id,
    (p -> 'template') || jsonb_build_object('id', gen_random_uuid(), 'date', v_start, 'status', 'active')
      - 'recurring_rule_id' - 'occurrence_date' - 'import_fingerprint',
    '{}'::jsonb) -> 'tx';
  v_template := v_template - 'id' - 'date' - 'status' - 'recurring_rule_id' - 'occurrence_date' - 'import_fingerprint';

  insert into fulla.recurring_rules (id, household_id, name, template, schedule, start_date, end_date, auto_create, active, archived)
  values (v_id, p_household_id, v_name, v_template, fulla.validate_schedule(p -> 'schedule'), v_start, v_end,
          fulla.json_bool(p, 'auto_create', false), fulla.json_bool(p, 'active', true), fulla.json_bool(p, 'archived', false))
  on conflict (id) do update
     set name = excluded.name, template = excluded.template, schedule = excluded.schedule,
         start_date = excluded.start_date, end_date = excluded.end_date,
         auto_create = excluded.auto_create, active = excluded.active, archived = excluded.archived;
  return v_id;
end;
$$;

create or replace function public.fulla_schema_version() returns integer
language sql immutable security definer
set search_path = ''
as $$
  select 21;
$$;

revoke all on function public.fulla_schema_version() from public, anon;
grant execute on function public.fulla_schema_version() to authenticated;
