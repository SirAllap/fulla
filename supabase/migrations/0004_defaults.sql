-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- What a new household starts with: a neutral set of categories and two
-- accounts, in the household's language. No people, no amounts.
--
-- The data is testdata/defaults/categories.json, embedded verbatim; the phone
-- uses the same file for local mode, and a test checks the two are identical.

create function fulla.defaults() returns jsonb
language sql immutable
as $defaults$
  select '{"categories":[{"key":"housing","applies_to":"expense","icon":"home","color_index":0,"name":{"en":"Housing","es":"Vivienda"}},{"key":"groceries","applies_to":"expense","icon":"shopping_cart","color_index":1,"name":{"en":"Groceries","es":"Supermercado"}},{"key":"eating_out","applies_to":"expense","icon":"restaurant","color_index":2,"name":{"en":"Eating out","es":"Comer fuera"}},{"key":"transport","applies_to":"expense","icon":"directions_bus","color_index":3,"name":{"en":"Transport","es":"Transporte"}},{"key":"utilities","applies_to":"expense","icon":"bolt","color_index":4,"name":{"en":"Utilities","es":"Suministros"}},{"key":"subscriptions","applies_to":"expense","icon":"subscriptions","color_index":5,"name":{"en":"Subscriptions","es":"Suscripciones"}},{"key":"health","applies_to":"expense","icon":"favorite","color_index":6,"name":{"en":"Health","es":"Salud"}},{"key":"shopping","applies_to":"expense","icon":"shopping_bag","color_index":7,"name":{"en":"Shopping","es":"Compras"}},{"key":"leisure","applies_to":"expense","icon":"sports_esports","color_index":8,"name":{"en":"Leisure","es":"Ocio"}},{"key":"education","applies_to":"expense","icon":"school","color_index":9,"name":{"en":"Education","es":"Educación"}},{"key":"gifts","applies_to":"expense","icon":"redeem","color_index":10,"name":{"en":"Gifts","es":"Regalos"}},{"key":"other","applies_to":"expense","icon":"more_horiz","color_index":11,"name":{"en":"Other","es":"Otros gastos"}},{"key":"salary","applies_to":"income","icon":"payments","color_index":0,"name":{"en":"Salary","es":"Salario"}},{"key":"other_income","applies_to":"income","icon":"savings","color_index":1,"name":{"en":"Other income","es":"Otros ingresos"}}],"uncategorized":{"key":"uncategorized","applies_to":"both","icon":"help","color_index":11,"name":{"en":"Uncategorized","es":"Sin categoría"}},"accounts":[{"key":"cash","type":"cash","name":{"en":"Cash","es":"Efectivo"}},{"key":"main","type":"checking","name":{"en":"Main account","es":"Cuenta principal"}}]}'::jsonb;
$defaults$;

-- The languages Fulla ships defaults for; anything else gets English.
create function fulla.lang_of(p_locale text) returns text
language sql immutable
as $$
  select case lower(split_part(coalesce(p_locale, ''), '-', 1)) when 'es' then 'es' else 'en' end;
$$;

-- Inserts the default categories and accounts into a household.
create function fulla.seed_defaults(p_household_id uuid, p_locale text) returns void
language plpgsql
as $$
declare
  v_lang text := fulla.lang_of(p_locale);
  c jsonb;
  i integer := 0;
begin
  for c in select value from jsonb_array_elements(fulla.defaults() -> 'categories') loop
    insert into fulla.categories (id, household_id, name, applies_to, icon, color_index, sort)
    values (gen_random_uuid(), p_household_id, c -> 'name' ->> v_lang, c ->> 'applies_to',
            c ->> 'icon', (c ->> 'color_index')::smallint, i);
    i := i + 1;
  end loop;
  i := 0;
  for c in select value from jsonb_array_elements(fulla.defaults() -> 'accounts') loop
    insert into fulla.accounts (id, household_id, name, type, sort)
    values (gen_random_uuid(), p_household_id, c -> 'name' ->> v_lang, c ->> 'type', i);
    i := i + 1;
  end loop;
end;
$$;

-- The "Uncategorized" category, created the first time something needs it
-- (an import with no matching rule). Returns its id.
create function fulla.ensure_uncategorized(p_household_id uuid) returns uuid
language plpgsql
as $$
declare
  v_lang text := (select fulla.lang_of(h.locale) from fulla.households h where h.id = p_household_id);
  v_name text := fulla.defaults() -> 'uncategorized' -> 'name' ->> v_lang;
  v_id uuid;
begin
  select c.id into v_id from fulla.categories c
   where c.household_id = p_household_id and c.parent_id is null and not c.archived
     and fulla.normalize_name(c.name) = fulla.normalize_name(v_name);
  if v_id is null then
    v_id := gen_random_uuid();
    insert into fulla.categories (id, household_id, name, applies_to, icon, color_index, sort)
    values (v_id, p_household_id, v_name, 'both', fulla.defaults() -> 'uncategorized' ->> 'icon',
            (fulla.defaults() -> 'uncategorized' ->> 'color_index')::smallint, 1000);
  end if;
  return v_id;
end;
$$;

revoke all on all functions in schema fulla from public, anon, authenticated;
