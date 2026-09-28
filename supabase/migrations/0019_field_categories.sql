-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- A custom field can be limited to some categories: "For whom" (Rex, Tom,
-- All) asked only under Pets › Vet, as one more level below the category.
-- Null means every category, as before. A category listed covers its
-- subcategories. The bundle carries the column with no change (to_jsonb of
-- each field), and fulla.save_field never names it, so editing a field keeps
-- it: it is set through its own function, like the salary mark was.

alter table fulla.custom_fields add column category_ids uuid[];

/*
 * Limits a field to categories of this household, or lifts the limit (null
 * or an empty list). Returns the config bundle.
 */
create function public.fulla_field_set_categories(p_household_id uuid, p_field_id uuid, p_category_ids jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_ids uuid[];
begin
  perform fulla.require_member(p_household_id, 'admin');
  if p_category_ids is not null and jsonb_typeof(p_category_ids) not in ('array', 'null') then
    perform fulla.invalid('`p_category_ids` must be a list of category ids.');
  end if;
  v_ids := array(select fulla.try_uuid(e) from jsonb_array_elements_text(
             case when jsonb_typeof(p_category_ids) = 'array' then p_category_ids else '[]'::jsonb end) as e);
  if not exists (select 1 from fulla.custom_fields f where f.household_id = p_household_id and f.id = p_field_id) then
    perform fulla.invalid('There is no such field in this household.');
  end if;
  if exists (
    select 1 from unnest(v_ids) as c(id)
     where not exists (select 1 from fulla.categories k where k.household_id = p_household_id and k.id = c.id)
  ) then
    perform fulla.invalid('Every category must be one of this household.');
  end if;
  update fulla.custom_fields f
     set category_ids = case when cardinality(v_ids) > 0 then v_ids end
   where f.household_id = p_household_id and f.id = p_field_id;
  return fulla.config_bundle(p_household_id);
end;
$$;

create or replace function public.fulla_schema_version() returns integer
language sql immutable security definer
set search_path = ''
as $$
  select 19;
$$;

-- ── grants ───────────────────────────────────────────────────────────────────

revoke all on function public.fulla_field_set_categories(uuid, uuid, jsonb) from public, anon;
grant execute on function public.fulla_field_set_categories(uuid, uuid, jsonb) to authenticated;
revoke all on function public.fulla_schema_version() from public, anon;
grant execute on function public.fulla_schema_version() to authenticated;
