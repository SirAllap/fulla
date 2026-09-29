-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- A monthly recurring item can name the months it falls due in: a quarterly
-- charge (January, April, July, October) or an irregular calendar (October
-- and December). `by_months` is a list of 1..12 next to `by_month_day`, for
-- `monthly` only, and then `interval` is 1: the list is the calendar. Without
-- it nothing changes: "every N months" was already accepted, counted from the
-- start month. Only the validator learns the key; the schedule is stored as
-- given and the phones work out the dates.

create or replace function fulla.validate_schedule(p jsonb) returns jsonb
language plpgsql immutable
as $$
declare
  v_freq text := p ->> 'freq';
  v_interval integer := fulla.json_int(p, 'interval', 1, 1, 365);
  v_days integer[];
  v_months integer[];
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
    if p ? 'by_months' and jsonb_typeof(p -> 'by_months') <> 'null' then
      if jsonb_typeof(p -> 'by_months') <> 'array' then
        perform fulla.invalid('`by_months` must be a list of months from 1 to 12.');
      end if;
      select array_agg(distinct d::integer order by d::integer) into v_months
        from jsonb_array_elements_text(p -> 'by_months') d where d ~ '^([1-9]|1[0-2])$';
      if coalesce(cardinality(v_months), 0) <> jsonb_array_length(p -> 'by_months') then
        perform fulla.invalid('`by_months` must list months from 1 to 12, each once.');
      end if;
      if v_months is not null and v_interval <> 1 then
        perform fulla.invalid('A schedule with `by_months` has `interval` 1: the list is the calendar.');
      end if;
      if v_months is not null then
        return jsonb_build_object('freq', v_freq, 'interval', v_interval, 'by_month_day', v_day, 'by_months', to_jsonb(v_months));
      end if;
    end if;
    return jsonb_build_object('freq', v_freq, 'interval', v_interval, 'by_month_day', v_day);
  end if;
  return jsonb_build_object('freq', v_freq, 'interval', v_interval);
end;
$$;

create or replace function public.fulla_schema_version() returns integer
language sql immutable security definer
set search_path = ''
as $$
  select 20;
$$;

revoke all on function public.fulla_schema_version() from public, anon;
grant execute on function public.fulla_schema_version() to authenticated;
