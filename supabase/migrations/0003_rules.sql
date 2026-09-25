-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- Domain rules the server enforces.
--
-- Several of these exist twice, here and in core/, because the phone and the
-- database must reach the same answer about the same row. Each such pair is
-- held together by a JSON file of test vectors under testdata/vectors/ that
-- both test suites read. Change a rule on one side and the other side's tests
-- go red until it follows.

-- ── small helpers ────────────────────────────────────────────────────────────

-- ISO-8601 in UTC with milliseconds and a Z, the only timestamp format on the wire.
create function fulla.iso(p timestamptz) returns text
language sql immutable
as $$ select to_char(p at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'); $$;

create function fulla.try_uuid(p text) returns uuid
language plpgsql immutable
as $$
begin
  return p::uuid;
exception when others then
  return null;
end;
$$;

create function fulla.try_date(p text) returns date
language plpgsql immutable
as $$
begin
  if p is null or p !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' then
    return null;
  end if;
  return p::date;
exception when others then
  return null;
end;
$$;

-- An optional uuid field of a JSON object: null when absent or null, and a
-- validation error when present but not a uuid.
create function fulla.json_uuid(p_obj jsonb, p_key text) returns uuid
language plpgsql immutable
as $$
declare
  v text := p_obj ->> p_key;
  u uuid;
begin
  if v is null then
    return null;
  end if;
  u := fulla.try_uuid(v);
  if u is null then
    perform fulla.invalid(format('`%s` must be a uuid.', p_key));
  end if;
  return u;
end;
$$;

-- An integer field of a JSON object, accepting a JSON number or a string of
-- digits. Never goes through a float.
create function fulla.json_bigint(p_obj jsonb, p_key text) returns bigint
language plpgsql immutable
as $$
declare
  v jsonb := p_obj -> p_key;
  t text;
begin
  if v is null or jsonb_typeof(v) = 'null' then
    return null;
  end if;
  t := case jsonb_typeof(v) when 'number' then v::text when 'string' then v #>> '{}' else null end;
  if t is null or t !~ '^-?[0-9]{1,18}$' then
    perform fulla.invalid(format('`%s` must be an integer.', p_key));
  end if;
  return t::bigint;
end;
$$;

-- ── currencies (ISO 4217) ────────────────────────────────────────────────────

-- Minor units per currency, or null for a code Fulla does not know. Generated
-- from testdata/defaults/currencies.json; a test checks the two agree.
create function fulla.currency_minor_units(p_code text) returns smallint
language sql immutable
as $$
  select m.units::smallint
    from (values
      ('AED', 2), ('AFN', 2), ('ALL', 2), ('AMD', 2), ('AOA', 2), ('ARS', 2), ('AUD', 2), ('AWG', 2),
      ('AZN', 2), ('BAM', 2), ('BBD', 2), ('BDT', 2), ('BGN', 2), ('BHD', 3), ('BIF', 0), ('BMD', 2),
      ('BND', 2), ('BOB', 2), ('BRL', 2), ('BSD', 2), ('BTN', 2), ('BWP', 2), ('BYN', 2), ('BZD', 2),
      ('CAD', 2), ('CDF', 2), ('CHF', 2), ('CLP', 0), ('CNY', 2), ('COP', 2), ('CRC', 2), ('CUP', 2),
      ('CVE', 2), ('CZK', 2), ('DJF', 0), ('DKK', 2), ('DOP', 2), ('DZD', 2), ('EGP', 2), ('ERN', 2),
      ('ETB', 2), ('EUR', 2), ('FJD', 2), ('FKP', 2), ('GBP', 2), ('GEL', 2), ('GHS', 2), ('GIP', 2),
      ('GMD', 2), ('GNF', 0), ('GTQ', 2), ('GYD', 2), ('HKD', 2), ('HNL', 2), ('HTG', 2), ('HUF', 2),
      ('IDR', 2), ('ILS', 2), ('INR', 2), ('IQD', 3), ('IRR', 2), ('ISK', 0), ('JMD', 2), ('JOD', 3),
      ('JPY', 0), ('KES', 2), ('KGS', 2), ('KHR', 2), ('KMF', 0), ('KPW', 2), ('KRW', 0), ('KWD', 3),
      ('KYD', 2), ('KZT', 2), ('LAK', 2), ('LBP', 2), ('LKR', 2), ('LRD', 2), ('LSL', 2), ('LYD', 3),
      ('MAD', 2), ('MDL', 2), ('MGA', 2), ('MKD', 2), ('MMK', 2), ('MNT', 2), ('MOP', 2), ('MRU', 2),
      ('MUR', 2), ('MVR', 2), ('MWK', 2), ('MXN', 2), ('MYR', 2), ('MZN', 2), ('NAD', 2), ('NGN', 2),
      ('NIO', 2), ('NOK', 2), ('NPR', 2), ('NZD', 2), ('OMR', 3), ('PAB', 2), ('PEN', 2), ('PGK', 2),
      ('PHP', 2), ('PKR', 2), ('PLN', 2), ('PYG', 0), ('QAR', 2), ('RON', 2), ('RSD', 2), ('RUB', 2),
      ('RWF', 0), ('SAR', 2), ('SBD', 2), ('SCR', 2), ('SDG', 2), ('SEK', 2), ('SGD', 2), ('SHP', 2),
      ('SLE', 2), ('SOS', 2), ('SRD', 2), ('SSP', 2), ('STN', 2), ('SVC', 2), ('SYP', 2), ('SZL', 2),
      ('THB', 2), ('TJS', 2), ('TMT', 2), ('TND', 3), ('TOP', 2), ('TRY', 2), ('TTD', 2), ('TWD', 2),
      ('TZS', 2), ('UAH', 2), ('UGX', 0), ('USD', 2), ('UYU', 2), ('UZS', 2), ('VED', 2), ('VES', 2),
      ('VND', 0), ('VUV', 0), ('WST', 2), ('XAF', 0), ('XCD', 2), ('XCG', 2), ('XOF', 0), ('XPF', 0),
      ('YER', 2), ('ZAR', 2), ('ZMW', 2), ('ZWG', 2)
    ) as m (code, units)
   where m.code = p_code;
$$;

-- ── accounting period [shared rule: testdata/vectors/period.json] ────────────

/*
 * The period (YYYY-MM) a transaction counts in.
 *
 * With period_start_day S > 1, a period runs from day S of one month to day
 * S-1 of the next and is named after the month it ends in: with S = 20,
 * 20 March to 19 April is "April".
 *
 * With S = 1 the period is the calendar month, except that a household may set
 * income_shift_day D: fixed income dated on or after day D counts in the next
 * month. That is for salaries paid in the last days of a month to fund the
 * next one. Only income moves, and only fixed income.
 */
create function fulla.period_of(p_date date, p_kind text, p_recurrence text,
                                p_start_day integer, p_shift_day integer) returns text
language sql immutable parallel safe
as $$
  select to_char(
    case
      when coalesce(p_start_day, 1) > 1
           and extract(day from p_date) >= p_start_day
        then p_date + interval '1 month'
      when coalesce(p_start_day, 1) = 1
           and p_shift_day is not null
           and p_kind = 'income'
           and p_recurrence = 'fixed'
           and extract(day from p_date) >= p_shift_day
        then p_date + interval '1 month'
      else p_date::timestamp
    end,
    'YYYY-MM');
$$;

-- ── splits [shared rule: testdata/vectors/allocate.json] ─────────────────────

/*
 * Who owes what of an amount, as {member_id: minor units}. The parts always
 * add up to the amount exactly.
 *
 *   {"mode":"equal",  "members":["m1","m2"]}
 *   {"mode":"shares", "shares":{"m1":2,"m2":1}}
 *   {"mode":"exact",  "amounts":{"m1":1500,"m2":500}}
 *
 * equal and shares use the largest remainder method: everyone gets the floor
 * of their exact share, and the units left over go one each to the largest
 * fractional remainders, ties broken by member id in byte order. That makes
 * the result independent of the order the members were listed in, which is
 * what lets the phone and the server agree to the unit.
 */
create function fulla.split_shares(p_amount bigint, p_split jsonb) returns jsonb
language sql immutable
as $$
  with weights as (
    select m.value as member_id, 1::numeric as weight
      from jsonb_array_elements_text(p_split -> 'members') m
     where p_split ->> 'mode' = 'equal'
    union all
    select s.key, s.value::numeric
      from jsonb_each_text(p_split -> 'shares') s
     where p_split ->> 'mode' = 'shares'
  ),
  total as (
    select sum(weight) as w from weights
  ),
  base as (
    select member_id,
           div(p_amount * weight, total.w) as part,
           mod(p_amount * weight, total.w) as remainder
      from weights, total
  ),
  ranked as (
    select member_id, part,
           row_number() over (order by remainder desc, member_id collate "C" asc) as rn
      from base
  ),
  leftover as (
    select p_amount - sum(part) as n from base
  )
  select case p_split ->> 'mode'
    when 'exact' then (
      select coalesce(jsonb_object_agg(a.key, a.value::bigint), '{}'::jsonb)
        from jsonb_each_text(p_split -> 'amounts') a)
    else (
      select coalesce(jsonb_object_agg(member_id, (part + case when rn <= leftover.n then 1 else 0 end)::bigint), '{}'::jsonb)
        from ranked, leftover)
  end;
$$;

-- Validates a split against a household and an amount; returns it normalised
-- (member ids lower-cased) or fails.
create function fulla.validate_split(p_household_id uuid, p_split jsonb, p_amount bigint) returns jsonb
language plpgsql stable
as $$
declare
  v_mode text := p_split ->> 'mode';
  v_ids text[];
  v_sum numeric;
  v_out jsonb;
  k text;
  v text;
begin
  if jsonb_typeof(p_split) <> 'object' then
    perform fulla.invalid('`split` must be an object.');
  end if;

  if v_mode = 'equal' then
    if jsonb_typeof(p_split -> 'members') <> 'array' or jsonb_array_length(p_split -> 'members') = 0 then
      perform fulla.invalid('An equal split needs at least one member.');
    end if;
    select array_agg(lower(e)) into v_ids from jsonb_array_elements_text(p_split -> 'members') e;
    v_out := jsonb_build_object('mode', 'equal', 'members', to_jsonb(v_ids));
  elsif v_mode = 'shares' then
    if jsonb_typeof(p_split -> 'shares') <> 'object' or p_split -> 'shares' = '{}'::jsonb then
      perform fulla.invalid('A split by shares needs at least one member.');
    end if;
    v_out := '{}'::jsonb;
    for k, v in select key, value from jsonb_each_text(p_split -> 'shares') loop
      if v !~ '^[0-9]{1,4}$' or v::integer not between 1 and 1000 then
        perform fulla.invalid('Shares must be whole numbers from 1 to 1000.');
      end if;
      v_out := v_out || jsonb_build_object(lower(k), v::integer);
    end loop;
    select array_agg(key) into v_ids from jsonb_object_keys(v_out) key;
    v_out := jsonb_build_object('mode', 'shares', 'shares', v_out);
  elsif v_mode = 'exact' then
    if jsonb_typeof(p_split -> 'amounts') <> 'object' or p_split -> 'amounts' = '{}'::jsonb then
      perform fulla.invalid('An exact split needs at least one member.');
    end if;
    v_out := '{}'::jsonb;
    v_sum := 0;
    for k, v in select key, value from jsonb_each_text(p_split -> 'amounts') loop
      if v !~ '^[0-9]{1,18}$' then
        perform fulla.invalid('Exact split amounts must be whole minor units, zero or more.');
      end if;
      v_sum := v_sum + v::numeric;
      v_out := v_out || jsonb_build_object(lower(k), v::bigint);
    end loop;
    if v_sum <> p_amount then
      perform fulla.invalid(format('An exact split must add up to the amount (%s), not %s.', p_amount, v_sum));
    end if;
    select array_agg(key) into v_ids from jsonb_object_keys(v_out) key;
    v_out := jsonb_build_object('mode', 'exact', 'amounts', v_out);
  else
    perform fulla.invalid('`split.mode` must be equal, shares or exact.');
  end if;

  if (select count(*) from unnest(v_ids) i) <> (select count(distinct i) from unnest(v_ids) i) then
    perform fulla.invalid('A member appears twice in the split.');
  end if;
  if exists (
    select 1 from unnest(v_ids) i
     where fulla.try_uuid(i) is null
        or not exists (select 1 from fulla.members m where m.household_id = p_household_id and m.id = fulla.try_uuid(i))
  ) then
    perform fulla.invalid('Every member in a split must belong to the household.');
  end if;
  return v_out;
end;
$$;

-- ── custom field values ──────────────────────────────────────────────────────

/*
 * One value of one field, checked and put in canonical form, or fails.
 * Returns {"value": <jsonb>, "warning": <text or null>}.
 *
 *   text        string, at most 500 characters
 *   number      canonical decimal string ("12.5"), from a JSON number or string
 *   money       integer minor units
 *   date        "YYYY-MM-DD"
 *   select      string; an unknown option is kept with a warning, never lost
 *   multiselect array of strings; same rule for unknown options
 *   boolean     true or false
 *   member      id of a member of the household
 */
create function fulla.field_value(p_household_id uuid, p_field fulla.custom_fields, p_value jsonb) returns jsonb
language plpgsql stable
as $$
declare
  v_type text := jsonb_typeof(p_value);
  v_text text := case when jsonb_typeof(p_value) in ('string', 'number', 'boolean') then p_value #>> '{}' end;
  v_warning text;
  v_label text := coalesce(p_field.labels ->> 'en', p_field.labels ->> 'es', p_field.key);
  e text;
begin
  case p_field.type
    when 'text' then
      if v_type <> 'string' or char_length(v_text) > 500 then
        perform fulla.invalid(format('«%s» must be text of at most 500 characters.', v_label));
      end if;
      return jsonb_build_object('value', to_jsonb(v_text));
    when 'number' then
      if v_type not in ('string', 'number') or v_text !~ '^-?[0-9]{1,15}(\.[0-9]{1,10})?$' then
        perform fulla.invalid(format('«%s» must be a number.', v_label));
      end if;
      return jsonb_build_object('value', to_jsonb(trim_scale(v_text::numeric)::text));
    when 'money' then
      if v_type not in ('string', 'number') or v_text !~ '^-?[0-9]{1,18}$' then
        perform fulla.invalid(format('«%s» must be an amount in minor units.', v_label));
      end if;
      return jsonb_build_object('value', to_jsonb(v_text::bigint));
    when 'date' then
      if v_type <> 'string' or fulla.try_date(v_text) is null then
        perform fulla.invalid(format('«%s» must be a date (YYYY-MM-DD).', v_label));
      end if;
      return jsonb_build_object('value', to_jsonb(v_text));
    when 'select' then
      if v_type <> 'string' or char_length(v_text) not between 1 and 100 then
        perform fulla.invalid(format('«%s» must be one option.', v_label));
      end if;
      if cardinality(p_field.options) > 0 and not v_text = any (p_field.options) then
        v_warning := format('unknown_option:%s', p_field.key);
      end if;
      return jsonb_build_object('value', to_jsonb(v_text), 'warning', v_warning);
    when 'multiselect' then
      if v_type <> 'array' or exists (
        select 1 from jsonb_array_elements(p_value) x
         where jsonb_typeof(x) <> 'string' or char_length(x #>> '{}') not between 1 and 100
      ) then
        perform fulla.invalid(format('«%s» must be a list of options.', v_label));
      end if;
      if cardinality(p_field.options) > 0 then
        for e in select x from jsonb_array_elements_text(p_value) x loop
          if not e = any (p_field.options) then
            v_warning := format('unknown_option:%s', p_field.key);
          end if;
        end loop;
      end if;
      return jsonb_build_object('value', p_value, 'warning', v_warning);
    when 'boolean' then
      if v_type <> 'boolean' then
        perform fulla.invalid(format('«%s» must be true or false.', v_label));
      end if;
      return jsonb_build_object('value', p_value);
    when 'member' then
      if v_type <> 'string' or not exists (
        select 1 from fulla.members m where m.household_id = p_household_id and m.id = fulla.try_uuid(v_text)
      ) then
        perform fulla.invalid(format('«%s» must be a member of the household.', v_label));
      end if;
      return jsonb_build_object('value', to_jsonb(lower(v_text)));
  end case;
end;
$$;

-- A field's default_value (always text) as the JSON value it stands for.
create function fulla.field_default(p_field fulla.custom_fields) returns jsonb
language sql immutable
as $$
  select case
    when p_field.default_value is null then null
    when p_field.type = 'money' then to_jsonb(p_field.default_value::bigint)
    when p_field.type = 'boolean' then to_jsonb(p_field.default_value::boolean)
    when p_field.type = 'multiselect' then p_field.default_value::jsonb
    else to_jsonb(p_field.default_value)
  end;
$$;

/*
 * Merges incoming custom field values into the stored ones.
 *
 * A key that is absent keeps its stored value; an explicit null clears it.
 * This is what lets a phone that has not yet heard of a new field save a row
 * without blanking the value another phone put there. A key for a field that
 * does not exist is dropped with a warning rather than failing the whole row.
 *
 * Returns {"extras": {...}, "warnings": [...]}.
 */
create function fulla.merge_extras(p_household_id uuid, p_kind text, p_incoming jsonb, p_stored jsonb) returns jsonb
language plpgsql stable
as $$
declare
  v_out jsonb := coalesce(p_stored, '{}'::jsonb);
  v_warnings jsonb := '[]'::jsonb;
  v_field fulla.custom_fields;
  v_checked jsonb;
  k text;
  v jsonb;
begin
  if p_incoming is not null and jsonb_typeof(p_incoming) <> 'object' then
    perform fulla.invalid('`extras` must be an object.');
  end if;

  for k, v in select key, value from jsonb_each(coalesce(p_incoming, '{}'::jsonb)) loop
    select * into v_field from fulla.custom_fields f where f.household_id = p_household_id and f.key = k;
    if v_field.id is null then
      v_warnings := v_warnings || to_jsonb(format('unknown_field:%s', k));
      continue;
    end if;
    if jsonb_typeof(v) = 'null' then
      v_out := v_out - k;
      continue;
    end if;
    v_checked := fulla.field_value(p_household_id, v_field, v);
    v_out := v_out || jsonb_build_object(k, v_checked -> 'value');
    if v_checked ->> 'warning' is not null then
      v_warnings := v_warnings || to_jsonb(v_checked ->> 'warning');
    end if;
  end loop;

  for v_field in
    select * from fulla.custom_fields f
     where f.household_id = p_household_id and not f.archived and f.required and p_kind = any (f.applies_to)
  loop
    if not v_out ? v_field.key then
      if v_field.default_value is null then
        perform fulla.invalid(format('«%s» is required.', coalesce(v_field.labels ->> 'en', v_field.key)));
      end if;
      v_out := v_out || jsonb_build_object(v_field.key, fulla.field_default(v_field));
    end if;
  end loop;

  return jsonb_build_object('extras', v_out, 'warnings', v_warnings);
end;
$$;

-- ── transactions [shared rule: testdata/vectors/validate_transaction.json] ───

/*
 * Checks a transaction as a client sent it and returns it in canonical form,
 * with exactly the columns of fulla.transactions that a client controls.
 * Fails with validation_failed and a message naming the problem.
 *
 * What each kind requires (anything not listed must be empty):
 *
 *   expense     category (expense or both), paid_by; split when the household
 *               has two or more active members; account optional
 *   income      category (income or both); account and paid_by optional
 *   refund      category (expense or both), paid_by (who got the money back);
 *               split as for expense; account optional
 *   transfer    account and to_account, different; paid_by optional
 *   settlement  paid_by (who pays) and to_member (who is paid), different;
 *               account optional
 *
 * Returns {"tx": {...}, "warnings": [...]}.
 */
create function fulla.validate_transaction(p_household_id uuid, p_tx jsonb, p_stored_extras jsonb default '{}')
returns jsonb
language plpgsql stable
as $$
declare
  v_id uuid;
  v_kind text := p_tx ->> 'kind';
  v_date date;
  v_amount bigint;
  v_category uuid;
  v_account uuid;
  v_to_account uuid;
  v_paid_by uuid;
  v_to_member uuid;
  v_split jsonb := p_tx -> 'split';
  v_recurrence text := coalesce(p_tx ->> 'recurrence', 'variable');
  v_note text := coalesce(p_tx ->> 'note', '');
  v_tags text[] := '{}';
  v_status text := coalesce(p_tx ->> 'status', 'active');
  v_rule uuid;
  v_occurrence date;
  v_fingerprint text := p_tx ->> 'import_fingerprint';
  v_original bigint;
  v_original_currency text := p_tx ->> 'original_currency';
  v_applies text;
  v_active_members integer;
  v_extras jsonb;
  t text;
begin
  if p_tx is null or jsonb_typeof(p_tx) <> 'object' then
    perform fulla.invalid('A transaction must be an object.');
  end if;

  v_id := fulla.json_uuid(p_tx, 'id');
  if v_id is null then
    perform fulla.invalid('`id` is required.');
  end if;
  if v_kind is null or v_kind not in ('expense', 'income', 'refund', 'transfer', 'settlement') then
    perform fulla.invalid('`kind` must be expense, income, refund, transfer or settlement.');
  end if;
  v_date := fulla.try_date(p_tx ->> 'date');
  if v_date is null then
    perform fulla.invalid('`date` must be a date (YYYY-MM-DD).');
  end if;
  v_amount := fulla.json_bigint(p_tx, 'amount_minor');
  if v_amount is null or v_amount <= 0 then
    perform fulla.invalid('`amount_minor` must be a whole number greater than zero.');
  end if;

  v_category := fulla.json_uuid(p_tx, 'category_id');
  v_account := fulla.json_uuid(p_tx, 'account_id');
  v_to_account := fulla.json_uuid(p_tx, 'to_account_id');
  v_paid_by := fulla.json_uuid(p_tx, 'paid_by_member_id');
  v_to_member := fulla.json_uuid(p_tx, 'to_member_id');
  if jsonb_typeof(v_split) = 'null' then
    v_split := null;
  end if;

  -- What each kind needs and forbids.
  if v_kind in ('expense', 'income', 'refund') then
    if v_category is null then
      perform fulla.invalid(format('A %s needs a category.', v_kind));
    end if;
  elsif v_category is not null then
    perform fulla.invalid(format('A %s has no category.', v_kind));
  end if;
  if v_kind in ('expense', 'refund', 'settlement') and v_paid_by is null then
    perform fulla.invalid(case v_kind
      when 'settlement' then 'A settlement needs the member who pays.'
      when 'refund' then 'A refund needs the member who got the money back.'
      else 'An expense needs the member who paid.' end);
  end if;
  if v_kind = 'transfer' then
    if v_account is null or v_to_account is null then
      perform fulla.invalid('A transfer needs an account to move money from and one to move it to.');
    end if;
    if v_account = v_to_account then
      perform fulla.invalid('A transfer needs two different accounts.');
    end if;
  elsif v_to_account is not null then
    perform fulla.invalid('Only a transfer has a destination account.');
  end if;
  if v_kind = 'settlement' then
    if v_to_member is null then
      perform fulla.invalid('A settlement needs the member who is paid.');
    end if;
    if v_to_member = v_paid_by then
      perform fulla.invalid('A settlement needs two different members.');
    end if;
  elsif v_to_member is not null then
    perform fulla.invalid('Only a settlement has a receiving member.');
  end if;

  -- References belong to this household.
  if v_category is not null then
    select c.applies_to into v_applies
      from fulla.categories c where c.household_id = p_household_id and c.id = v_category;
    if v_applies is null then
      perform fulla.invalid('That category does not exist in this household.');
    end if;
    if v_kind in ('expense', 'refund') and v_applies = 'income' then
      perform fulla.invalid(format('A %s needs a spending category.', v_kind));
    end if;
    if v_kind = 'income' and v_applies = 'expense' then
      perform fulla.invalid('Income needs an income category.');
    end if;
  end if;
  if exists (
    select 1 from unnest(array[v_account, v_to_account]) a
     where a is not null
       and not exists (select 1 from fulla.accounts x where x.household_id = p_household_id and x.id = a)
  ) then
    perform fulla.invalid('That account does not exist in this household.');
  end if;
  if exists (
    select 1 from unnest(array[v_paid_by, v_to_member]) m
     where m is not null
       and not exists (select 1 from fulla.members x where x.household_id = p_household_id and x.id = m)
  ) then
    perform fulla.invalid('That member does not exist in this household.');
  end if;

  -- Splits.
  if v_kind in ('expense', 'refund') then
    if v_split is null then
      select count(*) into v_active_members
        from fulla.members m where m.household_id = p_household_id and m.status = 'active';
      if v_active_members >= 2 then
        perform fulla.invalid(format('A %s in a household of several people needs a split.', v_kind));
      end if;
    else
      v_split := fulla.validate_split(p_household_id, v_split, v_amount);
    end if;
  elsif v_split is not null then
    perform fulla.invalid(format('A %s is not split.', v_kind));
  end if;

  -- Everything else.
  if v_recurrence not in ('fixed', 'variable') then
    perform fulla.invalid('`recurrence` must be fixed or variable.');
  end if;
  if v_kind in ('transfer', 'settlement') then
    v_recurrence := 'variable';
  end if;
  if char_length(v_note) > 500 then
    perform fulla.invalid('A note can be at most 500 characters.');
  end if;
  if p_tx ? 'tags' and jsonb_typeof(p_tx -> 'tags') not in ('array', 'null') then
    perform fulla.invalid('`tags` must be a list.');
  end if;
  if jsonb_typeof(p_tx -> 'tags') = 'array' then
    for t in select btrim(x) from jsonb_array_elements_text(p_tx -> 'tags') x loop
      if char_length(t) not between 1 and 30 then
        perform fulla.invalid('Each tag must be 1 to 30 characters.');
      end if;
      if not exists (select 1 from unnest(v_tags) e where fulla.normalize_name(e) = fulla.normalize_name(t)) then
        v_tags := v_tags || t;
      end if;
    end loop;
    if cardinality(v_tags) > 20 then
      perform fulla.invalid('At most 20 tags per transaction.');
    end if;
  end if;
  if v_status not in ('active', 'deleted') then
    perform fulla.invalid('`status` must be active or deleted.');
  end if;
  v_rule := fulla.json_uuid(p_tx, 'recurring_rule_id');
  v_occurrence := fulla.try_date(p_tx ->> 'occurrence_date');
  if (v_rule is null) <> (v_occurrence is null) then
    perform fulla.invalid('`recurring_rule_id` and `occurrence_date` go together.');
  end if;
  if v_rule is not null and not exists (
    select 1 from fulla.recurring_rules r where r.household_id = p_household_id and r.id = v_rule
  ) then
    perform fulla.invalid('That recurring rule does not exist in this household.');
  end if;
  if v_fingerprint is not null and char_length(v_fingerprint) not between 1 and 128 then
    perform fulla.invalid('`import_fingerprint` must be 1 to 128 characters.');
  end if;
  v_original := fulla.json_bigint(p_tx, 'original_amount_minor');
  if (v_original is null) <> (v_original_currency is null) then
    perform fulla.invalid('`original_amount_minor` and `original_currency` go together.');
  end if;
  if v_original is not null and (v_original <= 0 or fulla.currency_minor_units(v_original_currency) is null) then
    perform fulla.invalid('The original amount needs a positive amount and a known currency.');
  end if;

  v_extras := fulla.merge_extras(p_household_id, v_kind, p_tx -> 'extras', p_stored_extras);

  return jsonb_build_object(
    'tx', jsonb_build_object(
      'id', v_id,
      'kind', v_kind,
      'date', v_date,
      'amount_minor', v_amount,
      'category_id', v_category,
      'account_id', v_account,
      'to_account_id', v_to_account,
      'paid_by_member_id', v_paid_by,
      'to_member_id', v_to_member,
      'split', v_split,
      'recurrence', v_recurrence,
      'note', v_note,
      'tags', to_jsonb(v_tags),
      'extras', v_extras -> 'extras',
      'status', v_status,
      'recurring_rule_id', v_rule,
      'occurrence_date', v_occurrence,
      'import_fingerprint', v_fingerprint,
      'original_amount_minor', v_original,
      'original_currency', v_original_currency
    ),
    'warnings', v_extras -> 'warnings'
  );
end;
$$;

-- A stored transaction as the wire carries it.
create function fulla.tx_json(t fulla.transactions) returns jsonb
language sql stable
as $$
  select (to_jsonb(t) - 'household_id' - 'updated_by_user_id')
      || jsonb_build_object(
           'created_at', fulla.iso(t.created_at),
           'client_updated_at', fulla.iso(t.client_updated_at));
$$;

/*
 * The fields that differ between two versions of a transaction, for conflict
 * notes: [{"field", "before", "after"}]. Custom fields are listed as
 * extras.<key>. Server bookkeeping is never reported: it always differs, and
 * reporting it would make every merge look like a conflict.
 */
create function fulla.tx_diff(p_before jsonb, p_after jsonb) returns jsonb
language sql immutable
as $$
  with fields as (
    select k from unnest(array[
      'kind', 'date', 'amount_minor', 'category_id', 'account_id', 'to_account_id', 'paid_by_member_id',
      'to_member_id', 'split', 'recurrence', 'note', 'tags', 'status', 'recurring_rule_id',
      'occurrence_date', 'import_fingerprint', 'original_amount_minor', 'original_currency']) k
  ),
  core as (
    select k as field, p_before -> k as before, p_after -> k as after
      from fields
     where (p_before -> k) is distinct from (p_after -> k)
  ),
  extra_keys as (
    select jsonb_object_keys(coalesce(p_before -> 'extras', '{}')) as k
    union
    select jsonb_object_keys(coalesce(p_after -> 'extras', '{}'))
  ),
  extras as (
    select 'extras.' || k as field, p_before -> 'extras' -> k as before, p_after -> 'extras' -> k as after
      from extra_keys
     where (p_before -> 'extras' -> k) is distinct from (p_after -> 'extras' -> k)
  )
  select coalesce(jsonb_agg(jsonb_build_object('field', field, 'before', before, 'after', after) order by field), '[]')
    from (select * from core union all select * from extras) d;
$$;

revoke all on all functions in schema fulla from public, anon, authenticated;
