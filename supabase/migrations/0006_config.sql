-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- Household structure: accounts, categories, custom fields, budgets,
-- recurring rules, categorisation rules and import profiles.
--
-- Structure changes only with a connection (transactions are the offline
-- part), so these are plain request/response calls. Each returns the whole
-- config, the same bundle a pull carries when config_version has moved.
--
-- There are no delete functions. Everything is archived (`archived: true`) or
-- paused (`active: false`), and keeps its data.

-- ── the bundle ───────────────────────────────────────────────────────────────

create function fulla.config_bundle(p_household_id uuid) returns jsonb
language sql stable
as $$
  select jsonb_build_object(
    'config_version', h.config_version,
    'household', jsonb_build_object(
      'id', h.id, 'name', h.name, 'currency', h.currency, 'locale', h.locale,
      'period_start_day', h.period_start_day, 'income_shift_day', h.income_shift_day,
      'week_start', h.week_start, 'member_limit', h.member_limit),
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
        from fulla.import_profiles p where p.household_id = h.id), '[]')
  )
  from fulla.households h
  where h.id = p_household_id;
$$;

-- ── shared checks ────────────────────────────────────────────────────────────

-- The id of an item being saved: required, and not already used by another household.
create function fulla.item_id(p_household_id uuid, p_table regclass, p_item jsonb) returns uuid
language plpgsql stable
as $$
declare
  v_id uuid := fulla.json_uuid(p_item, 'id');
  v_owner uuid;
begin
  if v_id is null then
    perform fulla.invalid('`id` is required.');
  end if;
  execute format('select household_id from %s where id = $1', p_table) into v_owner using v_id;
  if v_owner is not null and v_owner <> p_household_id then
    perform fulla.fail(409, 'already_exists', 'That id is already in use.');
  end if;
  return v_id;
end;
$$;

create function fulla.json_bool(p_obj jsonb, p_key text, p_default boolean) returns boolean
language plpgsql immutable
as $$
begin
  if p_obj -> p_key is null or jsonb_typeof(p_obj -> p_key) = 'null' then
    return p_default;
  end if;
  if jsonb_typeof(p_obj -> p_key) <> 'boolean' then
    perform fulla.invalid(format('`%s` must be true or false.', p_key));
  end if;
  return (p_obj ->> p_key)::boolean;
end;
$$;

create function fulla.json_int(p_obj jsonb, p_key text, p_default integer, p_min integer, p_max integer) returns integer
language plpgsql immutable
as $$
declare
  v bigint := fulla.json_bigint(p_obj, p_key);
begin
  if v is null then
    return p_default;
  end if;
  if v not between p_min and p_max then
    perform fulla.invalid(format('`%s` must be from %s to %s.', p_key, p_min, p_max));
  end if;
  return v::integer;
end;
$$;

-- ── accounts ─────────────────────────────────────────────────────────────────

create function fulla.save_account(p_household_id uuid, p jsonb) returns uuid
language plpgsql volatile
as $$
declare
  v_id uuid := fulla.item_id(p_household_id, 'fulla.accounts', p);
  v_name text := btrim(coalesce(p ->> 'name', ''));
  v_type text := coalesce(p ->> 'type', 'other');
  v_archived boolean := fulla.json_bool(p, 'archived', false);
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

  insert into fulla.accounts (id, household_id, name, type, opening_balance_minor, sort, archived)
  values (v_id, p_household_id, v_name, v_type, coalesce(fulla.json_bigint(p, 'opening_balance_minor'), 0),
          fulla.json_int(p, 'sort', 0, -1000000, 1000000), v_archived)
  on conflict (id) do update
     set name = excluded.name, type = excluded.type, opening_balance_minor = excluded.opening_balance_minor,
         sort = excluded.sort, archived = excluded.archived;
  return v_id;
end;
$$;

-- ── categories ───────────────────────────────────────────────────────────────

create function fulla.save_category(p_household_id uuid, p jsonb) returns uuid
language plpgsql volatile
as $$
declare
  v_id uuid := fulla.item_id(p_household_id, 'fulla.categories', p);
  v_name text := btrim(coalesce(p ->> 'name', ''));
  v_parent uuid := fulla.json_uuid(p, 'parent_id');
  v_applies text := coalesce(p ->> 'applies_to', 'expense');
  v_icon text := coalesce(p ->> 'icon', 'label');
  v_archived boolean := fulla.json_bool(p, 'archived', false);
begin
  if char_length(v_name) not between 1 and 40 then
    perform fulla.invalid('A category name must be 1 to 40 characters.');
  end if;
  if v_applies not in ('expense', 'income', 'both') then
    perform fulla.invalid('`applies_to` must be expense, income or both.');
  end if;
  if v_icon !~ '^[a-z0-9_]{1,40}$' then
    perform fulla.invalid('`icon` must be a Material Symbols name.');
  end if;
  if v_parent is not null then
    if v_parent = v_id then
      perform fulla.invalid('A category cannot be its own parent.');
    end if;
    if not exists (select 1 from fulla.categories c
                    where c.household_id = p_household_id and c.id = v_parent and c.parent_id is null) then
      perform fulla.invalid('The parent must be a top-level category of this household.');
    end if;
    if exists (select 1 from fulla.categories c where c.household_id = p_household_id and c.parent_id = v_id) then
      perform fulla.invalid('A category with subcategories cannot become a subcategory.');
    end if;
  end if;
  if not v_archived and exists (
    select 1 from fulla.categories c
     where c.household_id = p_household_id and c.id <> v_id and not c.archived
       and c.parent_id is not distinct from v_parent
       and fulla.normalize_name(c.name) = fulla.normalize_name(v_name)
  ) then
    perform fulla.invalid(format('There is already a category called «%s» here.', v_name));
  end if;

  insert into fulla.categories (id, household_id, parent_id, name, applies_to, icon, color_index, sort, archived)
  values (v_id, p_household_id, v_parent, v_name, v_applies, v_icon,
          fulla.json_int(p, 'color_index', 0, 0, 11), fulla.json_int(p, 'sort', 0, -1000000, 1000000), v_archived)
  on conflict (id) do update
     set parent_id = excluded.parent_id, name = excluded.name, applies_to = excluded.applies_to,
         icon = excluded.icon, color_index = excluded.color_index, sort = excluded.sort, archived = excluded.archived;
  return v_id;
end;
$$;

-- ── custom fields ────────────────────────────────────────────────────────────

create function fulla.save_field(p_household_id uuid, p jsonb) returns uuid
language plpgsql volatile
as $$
declare
  v_id uuid := fulla.item_id(p_household_id, 'fulla.custom_fields', p);
  v_existing fulla.custom_fields;
  v_field fulla.custom_fields;
  v_reserved constant text[] := array[
    'id', 'household_id', 'kind', 'date', 'amount_minor', 'category_id', 'account_id', 'to_account_id',
    'paid_by_member_id', 'to_member_id', 'split', 'recurrence', 'note', 'tags', 'extras', 'status',
    'recurring_rule_id', 'occurrence_date', 'import_fingerprint', 'original_amount_minor',
    'original_currency', 'created_at', 'client_updated_at', 'server_seq', 'created_by_member_id',
    'updated_by_user_id'];
  v_label text;
begin
  select * into v_existing from fulla.custom_fields f where f.id = v_id;

  v_field.id := v_id;
  v_field.household_id := p_household_id;
  v_field.key := p ->> 'key';
  v_field.type := p ->> 'type';
  v_field.labels := p -> 'labels';
  v_field.required := fulla.json_bool(p, 'required', false);
  v_field.show_in_list := fulla.json_bool(p, 'show_in_list', false);
  v_field.archived := fulla.json_bool(p, 'archived', false);
  v_field.sort := fulla.json_int(p, 'sort', 0, -1000000, 1000000);
  v_field.default_value := p ->> 'default_value';

  if v_field.key is null or v_field.key !~ '^[a-z][a-z0-9_]{0,31}$' or v_field.key = any (v_reserved) then
    perform fulla.invalid('`key` must be lower case letters, digits and underscores, start with a letter, and not be a built-in name.');
  end if;
  if v_existing.id is not null and (v_existing.key <> v_field.key or v_existing.type <> v_field.type) then
    perform fulla.invalid('A field''s key and type cannot change once it exists.');
  end if;
  if v_existing.id is null and exists (
    select 1 from fulla.custom_fields f where f.household_id = p_household_id and f.key = v_field.key
  ) then
    perform fulla.invalid(format('There is already a field with key «%s».', v_field.key));
  end if;
  if v_field.type is null or v_field.type not in ('text', 'number', 'money', 'date', 'select', 'multiselect', 'boolean', 'member') then
    perform fulla.invalid('`type` must be text, number, money, date, select, multiselect, boolean or member.');
  end if;

  if jsonb_typeof(v_field.labels) <> 'object' then
    perform fulla.invalid('`labels` must be an object such as {"en": "Shop"}.');
  end if;
  v_field.labels := (
    select coalesce(jsonb_object_agg(key, btrim(value)), '{}'::jsonb)
      from jsonb_each_text(v_field.labels) where key in ('en', 'es') and btrim(value) <> '');
  if v_field.labels = '{}'::jsonb then
    perform fulla.invalid('A field needs a label in at least one language.');
  end if;
  for v_label in select value from jsonb_each_text(v_field.labels) loop
    if char_length(v_label) > 40 then
      perform fulla.invalid('A label can be at most 40 characters.');
    end if;
  end loop;

  if jsonb_typeof(p -> 'applies_to') <> 'array' then
    perform fulla.invalid('`applies_to` must be a list of transaction kinds.');
  end if;
  select array_agg(distinct k) into v_field.applies_to from jsonb_array_elements_text(p -> 'applies_to') k;
  if v_field.applies_to is null
     or not v_field.applies_to <@ array['expense', 'income', 'refund', 'transfer', 'settlement'] then
    perform fulla.invalid('`applies_to` must list one or more of expense, income, refund, transfer, settlement.');
  end if;

  if v_field.type in ('select', 'multiselect') then
    if jsonb_typeof(coalesce(p -> 'options', '[]')) <> 'array' then
      perform fulla.invalid('`options` must be a list.');
    end if;
    select coalesce(array_agg(btrim(o) order by n), '{}') into v_field.options
      from jsonb_array_elements_text(coalesce(p -> 'options', '[]')) with ordinality as x(o, n);
    if cardinality(v_field.options) > 50
       or exists (select 1 from unnest(v_field.options) o where char_length(o) not between 1 and 100)
       or cardinality(v_field.options) <> (select count(distinct o) from unnest(v_field.options) o) then
      perform fulla.invalid('Options must be up to 50 different values of 1 to 100 characters.');
    end if;
  else
    v_field.options := '{}';
    if jsonb_typeof(p -> 'options') = 'array' and jsonb_array_length(p -> 'options') > 0 then
      perform fulla.invalid('Only select and multiselect fields have options.');
    end if;
  end if;

  if v_field.default_value is not null then
    if not (case v_field.type
              when 'money' then v_field.default_value ~ '^-?[0-9]{1,18}$'
              when 'boolean' then v_field.default_value in ('true', 'false')
              when 'number' then v_field.default_value ~ '^-?[0-9]{1,15}(\.[0-9]{1,10})?$'
              when 'date' then fulla.try_date(v_field.default_value) is not null
              when 'member' then fulla.try_uuid(v_field.default_value) is not null
              when 'multiselect' then v_field.default_value ~ '^\[.*\]$'
              else char_length(v_field.default_value) <= 500
            end) then
      perform fulla.invalid('The default value does not fit the field''s type.');
    end if;
    perform fulla.field_value(p_household_id, v_field, fulla.field_default(v_field));
  end if;

  insert into fulla.custom_fields (id, household_id, key, labels, type, applies_to, required, options,
                                   default_value, show_in_list, sort, archived)
  values (v_field.id, p_household_id, v_field.key, v_field.labels, v_field.type, v_field.applies_to,
          v_field.required, v_field.options, v_field.default_value, v_field.show_in_list, v_field.sort, v_field.archived)
  on conflict (id) do update
     set labels = excluded.labels, applies_to = excluded.applies_to, required = excluded.required,
         options = excluded.options, default_value = excluded.default_value,
         show_in_list = excluded.show_in_list, sort = excluded.sort, archived = excluded.archived;
  return v_id;
end;
$$;

-- ── budgets ──────────────────────────────────────────────────────────────────

/*
 * A spending limit for a category. `period` is YYYY-MM for one period, or null
 * for the default that applies to every period without its own. An amount of
 * zero means "no limit this period" and overrides the default.
 */
create function fulla.save_budget(p_household_id uuid, p jsonb) returns uuid
language plpgsql volatile
as $$
declare
  v_id uuid := fulla.item_id(p_household_id, 'fulla.budgets', p);
  v_category uuid := fulla.json_uuid(p, 'category_id');
  v_period text := p ->> 'period';
  v_amount bigint := fulla.json_bigint(p, 'amount_minor');
  v_saved uuid;
begin
  if v_category is null or not exists (
    select 1 from fulla.categories c
     where c.household_id = p_household_id and c.id = v_category and c.applies_to in ('expense', 'both')
  ) then
    perform fulla.invalid('A budget needs a spending category of this household.');
  end if;
  if v_period is not null and v_period !~ '^[0-9]{4}-(0[1-9]|1[0-2])$' then
    perform fulla.invalid('`period` must be YYYY-MM, or null for every period.');
  end if;
  if v_amount is null or v_amount < 0 then
    perform fulla.invalid('`amount_minor` must be zero or more.');
  end if;

  if exists (select 1 from fulla.budgets b where b.id = v_id) then
    update fulla.budgets b set category_id = v_category, period = v_period, amount_minor = v_amount
     where b.id = v_id;
    return v_id;
  end if;
  insert into fulla.budgets (id, household_id, category_id, period, amount_minor)
  values (v_id, p_household_id, v_category, v_period, v_amount)
  on conflict (household_id, category_id, (coalesce(period, '*')))
  do update set amount_minor = excluded.amount_minor
  returning id into v_saved;
  return v_saved;
end;
$$;

-- ── recurring rules ──────────────────────────────────────────────────────────

/*
 *   {"freq": "daily" | "weekly" | "monthly" | "yearly",
 *    "interval": 1..365,
 *    "by_weekday": [1..7],      weekly only; 1 = Monday
 *    "by_month_day": 1..31|-1,  monthly and yearly; -1 = last day of the month
 *    "by_month": 1..12}         yearly only
 *
 * A day past the end of a month falls on its last day (31 → 30 April).
 */
create function fulla.validate_schedule(p jsonb) returns jsonb
language plpgsql immutable
as $$
declare
  v_freq text := p ->> 'freq';
  v_interval integer := fulla.json_int(p, 'interval', 1, 1, 365);
  v_days integer[];
  v_day integer;
  v_month integer;
begin
  if jsonb_typeof(p) <> 'object' then
    perform fulla.invalid('`schedule` must be an object.');
  end if;
  if v_freq is null or v_freq not in ('daily', 'weekly', 'monthly', 'yearly') then
    perform fulla.invalid('`schedule.freq` must be daily, weekly, monthly or yearly.');
  end if;
  if v_freq = 'weekly' then
    if jsonb_typeof(p -> 'by_weekday') <> 'array' then
      perform fulla.invalid('A weekly schedule needs `by_weekday`.');
    end if;
    select array_agg(distinct d::integer order by d::integer) into v_days
      from jsonb_array_elements_text(p -> 'by_weekday') d where d ~ '^[1-7]$';
    if v_days is null or cardinality(v_days) <> jsonb_array_length(p -> 'by_weekday') then
      perform fulla.invalid('`by_weekday` must list days from 1 (Monday) to 7 (Sunday), each once.');
    end if;
    return jsonb_build_object('freq', v_freq, 'interval', v_interval, 'by_weekday', to_jsonb(v_days));
  end if;
  if v_freq in ('monthly', 'yearly') then
    v_day := fulla.json_int(p, 'by_month_day', null, -1, 31);
    if v_day is null or v_day = 0 then
      perform fulla.invalid('A monthly or yearly schedule needs `by_month_day` (1 to 31, or -1 for the last day).');
    end if;
    if v_freq = 'yearly' then
      v_month := fulla.json_int(p, 'by_month', null, 1, 12);
      if v_month is null then
        perform fulla.invalid('A yearly schedule needs `by_month`.');
      end if;
      return jsonb_build_object('freq', v_freq, 'interval', v_interval, 'by_month_day', v_day, 'by_month', v_month);
    end if;
    return jsonb_build_object('freq', v_freq, 'interval', v_interval, 'by_month_day', v_day);
  end if;
  return jsonb_build_object('freq', v_freq, 'interval', v_interval);
end;
$$;

create function fulla.save_recurring(p_household_id uuid, p jsonb) returns uuid
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

  insert into fulla.recurring_rules (id, household_id, name, template, schedule, start_date, end_date, auto_create, active)
  values (v_id, p_household_id, v_name, v_template, fulla.validate_schedule(p -> 'schedule'), v_start, v_end,
          fulla.json_bool(p, 'auto_create', false), fulla.json_bool(p, 'active', true))
  on conflict (id) do update
     set name = excluded.name, template = excluded.template, schedule = excluded.schedule,
         start_date = excluded.start_date, end_date = excluded.end_date,
         auto_create = excluded.auto_create, active = excluded.active;
  return v_id;
end;
$$;

-- ── categorisation rules ─────────────────────────────────────────────────────

/*
 * "When an imported note contains <pattern>, then <action>." Compared with
 * normalize_name on both sides. The first active rule by `sort` wins.
 *
 *   action: {"category_id"?, "kind"?: "expense"|"income"|"transfer",
 *            "to_account_id"? (required for transfer), "tags"?: [...]}
 */
create function fulla.save_rule(p_household_id uuid, p jsonb) returns uuid
language plpgsql volatile
as $$
declare
  v_id uuid := fulla.item_id(p_household_id, 'fulla.categorization_rules', p);
  v_pattern text := btrim(coalesce(p ->> 'pattern', ''));
  v_action jsonb := p -> 'action';
  v_out jsonb := '{}'::jsonb;
  v_category uuid;
  v_to_account uuid;
begin
  if char_length(v_pattern) not between 1 and 100 then
    perform fulla.invalid('A rule''s pattern must be 1 to 100 characters.');
  end if;
  if jsonb_typeof(v_action) <> 'object' then
    perform fulla.invalid('`action` must be an object.');
  end if;
  v_category := fulla.json_uuid(v_action, 'category_id');
  if v_category is not null then
    if not exists (select 1 from fulla.categories c where c.household_id = p_household_id and c.id = v_category) then
      perform fulla.invalid('That category does not exist in this household.');
    end if;
    v_out := v_out || jsonb_build_object('category_id', v_category);
  end if;
  if v_action ? 'kind' then
    if v_action ->> 'kind' not in ('expense', 'income', 'transfer') then
      perform fulla.invalid('A rule can set the kind to expense, income or transfer.');
    end if;
    v_out := v_out || jsonb_build_object('kind', v_action ->> 'kind');
  end if;
  v_to_account := fulla.json_uuid(v_action, 'to_account_id');
  if v_action ->> 'kind' = 'transfer' and v_to_account is null then
    perform fulla.invalid('A rule that makes a transfer needs `to_account_id`.');
  end if;
  if v_to_account is not null then
    if not exists (select 1 from fulla.accounts a where a.household_id = p_household_id and a.id = v_to_account) then
      perform fulla.invalid('That account does not exist in this household.');
    end if;
    v_out := v_out || jsonb_build_object('to_account_id', v_to_account);
  end if;
  if v_action ? 'tags' then
    if jsonb_typeof(v_action -> 'tags') <> 'array' then
      perform fulla.invalid('`tags` must be a list.');
    end if;
    v_out := v_out || jsonb_build_object('tags', v_action -> 'tags');
  end if;
  if v_out = '{}'::jsonb then
    perform fulla.invalid('A rule must do something: set a category, a kind or tags.');
  end if;

  insert into fulla.categorization_rules (id, household_id, pattern, action, sort, active)
  values (v_id, p_household_id, v_pattern, v_out, fulla.json_int(p, 'sort', 0, -1000000, 1000000),
          fulla.json_bool(p, 'active', true))
  on conflict (id) do update
     set pattern = excluded.pattern, action = excluded.action, sort = excluded.sort, active = excluded.active;
  return v_id;
end;
$$;

-- ── import profiles ──────────────────────────────────────────────────────────

/*
 * How to read one CSV layout. The phone does the reading; the server only
 * checks the shape so a broken profile cannot reach the other phones.
 *
 *   {"encoding": "UTF-8"|"ISO-8859-1"|"Windows-1252", "delimiter": ","|";"|"\t"|"|",
 *    "skip_rows": 0, "has_header": true, "date_column": 0, "date_format": "dd/MM/yyyy",
 *    "amount": {"mode": "single", "column": 3, "negative_is_expense": true}
 *            | {"mode": "split", "debit_column": 3, "credit_column": 4},
 *    "decimal": "," | ".", "description_columns": [1, 2], "category_column": null}
 */
create function fulla.save_import_profile(p_household_id uuid, p jsonb) returns uuid
language plpgsql volatile
as $$
declare
  v_id uuid := fulla.item_id(p_household_id, 'fulla.import_profiles', p);
  v_name text := btrim(coalesce(p ->> 'name', ''));
  m jsonb := p -> 'mapping';
  v_account uuid := fulla.json_uuid(p, 'account_id');
begin
  if char_length(v_name) not between 1 and 60 then
    perform fulla.invalid('A profile name must be 1 to 60 characters.');
  end if;
  if coalesce(p ->> 'format', 'csv') <> 'csv' then
    perform fulla.invalid('Only CSV profiles are stored.');
  end if;
  if jsonb_typeof(m) <> 'object'
     or coalesce(m ->> 'encoding', 'UTF-8') not in ('UTF-8', 'ISO-8859-1', 'Windows-1252')
     or coalesce(m ->> 'delimiter', ',') not in (',', ';', E'\t', '|')
     or coalesce(m ->> 'decimal', '.') not in (',', '.')
     or fulla.json_int(m, 'skip_rows', 0, 0, 100) is null
     or fulla.json_int(m, 'date_column', null, 0, 200) is null
     or coalesce(m ->> 'date_format', '') !~ '^[dMy/.\- ]{6,12}$'
     or jsonb_typeof(m -> 'amount') <> 'object'
     or (m -> 'amount' ->> 'mode') not in ('single', 'split')
     or (m -> 'amount' ->> 'mode' = 'single' and fulla.json_int(m -> 'amount', 'column', null, 0, 200) is null)
     or (m -> 'amount' ->> 'mode' = 'split' and (fulla.json_int(m -> 'amount', 'debit_column', null, 0, 200) is null
                                                 or fulla.json_int(m -> 'amount', 'credit_column', null, 0, 200) is null))
     or jsonb_typeof(coalesce(m -> 'description_columns', '[]')) <> 'array'
  then
    perform fulla.invalid('The column mapping is incomplete or invalid.');
  end if;
  if v_account is not null and not exists (
    select 1 from fulla.accounts a where a.household_id = p_household_id and a.id = v_account
  ) then
    perform fulla.invalid('That account does not exist in this household.');
  end if;

  insert into fulla.import_profiles (id, household_id, name, format, mapping, account_id)
  values (v_id, p_household_id, v_name, 'csv', m, v_account)
  on conflict (id) do update
     set name = excluded.name, mapping = excluded.mapping, account_id = excluded.account_id;
  return v_id;
end;
$$;

-- ── API ──────────────────────────────────────────────────────────────────────

create function public.fulla_config_get(p_household_id uuid) returns jsonb
language plpgsql stable security definer
set search_path = ''
as $$
begin
  perform fulla.require_member(p_household_id, 'member');
  return fulla.config_bundle(p_household_id);
end;
$$;

create function public.fulla_account_upsert(p_household_id uuid, p_account jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
begin
  perform fulla.require_member(p_household_id, 'admin');
  perform fulla.save_account(p_household_id, p_account);
  return fulla.config_bundle(p_household_id);
end;
$$;

create function public.fulla_category_upsert(p_household_id uuid, p_category jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
begin
  perform fulla.require_member(p_household_id, 'admin');
  perform fulla.save_category(p_household_id, p_category);
  return fulla.config_bundle(p_household_id);
end;
$$;

create function public.fulla_field_upsert(p_household_id uuid, p_field jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
begin
  perform fulla.require_member(p_household_id, 'admin');
  perform fulla.save_field(p_household_id, p_field);
  return fulla.config_bundle(p_household_id);
end;
$$;

create function public.fulla_budget_upsert(p_household_id uuid, p_budget jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
begin
  perform fulla.require_member(p_household_id, 'admin');
  perform fulla.save_budget(p_household_id, p_budget);
  return fulla.config_bundle(p_household_id);
end;
$$;

/*
 * Copies every category's limit for one period, explicit or inherited from
 * the default, into explicit limits for another. Limits the target period
 * already has are left alone.
 */
create function public.fulla_budget_copy(p_household_id uuid, p_from_period text, p_to_period text) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
begin
  perform fulla.require_member(p_household_id, 'admin');
  if coalesce(p_from_period, '') !~ '^[0-9]{4}-(0[1-9]|1[0-2])$' or coalesce(p_to_period, '') !~ '^[0-9]{4}-(0[1-9]|1[0-2])$' then
    perform fulla.invalid('Both periods must be YYYY-MM.');
  end if;
  insert into fulla.budgets (id, household_id, category_id, period, amount_minor)
  select gen_random_uuid(), p_household_id, c.id, p_to_period,
         coalesce(explicit.amount_minor, fallback.amount_minor)
    from fulla.categories c
    left join fulla.budgets explicit
      on explicit.household_id = c.household_id and explicit.category_id = c.id and explicit.period = p_from_period
    left join fulla.budgets fallback
      on fallback.household_id = c.household_id and fallback.category_id = c.id and fallback.period is null
   where c.household_id = p_household_id
     and coalesce(explicit.amount_minor, fallback.amount_minor) is not null
  on conflict (household_id, category_id, (coalesce(period, '*'))) do nothing;
  return fulla.config_bundle(p_household_id);
end;
$$;

create function public.fulla_recurring_upsert(p_household_id uuid, p_rule jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
begin
  perform fulla.require_member(p_household_id, 'admin');
  perform fulla.save_recurring(p_household_id, p_rule);
  return fulla.config_bundle(p_household_id);
end;
$$;

create function public.fulla_rule_upsert(p_household_id uuid, p_rule jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
begin
  perform fulla.require_member(p_household_id, 'admin');
  perform fulla.save_rule(p_household_id, p_rule);
  return fulla.config_bundle(p_household_id);
end;
$$;

-- Any member may save an import profile: importing is a member's job, and a
-- profile only describes how to read a file.
create function public.fulla_import_profile_upsert(p_household_id uuid, p_profile jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
begin
  perform fulla.require_member(p_household_id, 'member');
  perform fulla.save_import_profile(p_household_id, p_profile);
  return fulla.config_bundle(p_household_id);
end;
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
