-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- Households, members and invites.
--
-- Every function in `public` here is SECURITY DEFINER with an empty
-- search_path, so every name is schema-qualified, and each one checks the
-- caller's membership and role itself before touching anything.

-- ── helpers ──────────────────────────────────────────────────────────────────

create function fulla.check_person(p_display_name text, p_initials text, p_color_index integer) returns void
language plpgsql immutable
as $$
begin
  if p_display_name is null or char_length(btrim(p_display_name)) not between 1 and 40 then
    perform fulla.invalid('A name must be 1 to 40 characters.');
  end if;
  if p_initials is null or char_length(btrim(p_initials)) not between 1 and 2 then
    perform fulla.invalid('Initials must be 1 or 2 characters.');
  end if;
  if p_color_index is null or p_color_index not between 0 and 9 then
    perform fulla.invalid('`color_index` must be between 0 and 9.');
  end if;
end;
$$;

create function fulla.check_household_fields(p_name text, p_currency text, p_locale text) returns void
language plpgsql immutable
as $$
begin
  if p_name is null or char_length(btrim(p_name)) not between 1 and 60 then
    perform fulla.invalid('A household name must be 1 to 60 characters.');
  end if;
  if p_currency is null or fulla.currency_minor_units(upper(p_currency)) is null then
    perform fulla.invalid('`currency` must be an ISO 4217 code Fulla knows.');
  end if;
  if p_locale is null or p_locale !~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$' then
    perform fulla.invalid('`locale` must be a language tag such as en-GB.');
  end if;
end;
$$;

create function fulla.active_member_count(p_household_id uuid) returns integer
language sql stable
as $$ select count(*)::integer from fulla.members m where m.household_id = p_household_id and m.status = 'active'; $$;

-- Ten characters from a 30-letter alphabet without look-alikes (0 O 1 I L U).
-- About 49 bits; accepting an invite is throttled per user on top of that.
create function fulla.new_invite_code() returns text
language plpgsql volatile
as $$
declare
  v_alphabet constant text := '23456789ABCDEFGHJKMNPQRSTVWXYZ';
  v_bytes bytea;
  v_code text;
  i integer;
begin
  loop
    -- Bytes 0-5 of a version 4 uuid are fully random.
    v_bytes := substr(uuid_send(gen_random_uuid()), 1, 6) || substr(uuid_send(gen_random_uuid()), 1, 6);
    v_code := '';
    for i in 0..9 loop
      v_code := v_code || substr(v_alphabet, (get_byte(v_bytes, i) % 30) + 1, 1);
    end loop;
    exit when not exists (select 1 from fulla.invites x where x.code = v_code);
  end loop;
  return v_code;
end;
$$;

create function fulla.normalize_invite_code(p text) returns text
language sql immutable
as $$ select upper(regexp_replace(coalesce(p, ''), '[^A-Za-z0-9]', '', 'g')); $$;

-- ── system ───────────────────────────────────────────────────────────────────

-- The app calls this first against a new backend: it proves the URL and key
-- are right, the schema is installed, and the API version is one it speaks.
create function public.fulla_ping() returns jsonb
language sql stable security definer
set search_path = ''
as $$
  select jsonb_build_object('api_version', 1, 'server_time', fulla.iso(now()), 'signed_in', auth.uid() is not null);
$$;

create function public.fulla_my_households() returns jsonb
language plpgsql stable security definer
set search_path = ''
as $$
declare
  v_uid uuid := fulla.require_user();
begin
  return coalesce((
    select jsonb_agg(jsonb_build_object(
             'household_id', h.id, 'name', h.name, 'currency', h.currency,
             'member_id', m.id, 'role', m.role) order by h.created_at)
      from fulla.members m
      join fulla.households h on h.id = m.household_id
     where m.user_id = v_uid and m.status = 'active'
  ), '[]'::jsonb);
end;
$$;

-- ── households ───────────────────────────────────────────────────────────────

create function public.fulla_household_create(
  p_name text, p_currency text, p_locale text,
  p_display_name text, p_initials text, p_color_index integer default 0
) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_uid uuid := fulla.require_user();
  v_household uuid;
  v_member uuid;
begin
  perform fulla.check_household_fields(p_name, p_currency, p_locale);
  perform fulla.check_person(p_display_name, p_initials, p_color_index);

  insert into fulla.households (name, currency, locale, created_by)
  values (btrim(p_name), upper(p_currency), p_locale, v_uid)
  returning id into v_household;

  insert into fulla.members (household_id, user_id, display_name, initials, color_index, role, joined_at)
  values (v_household, v_uid, btrim(p_display_name), btrim(p_initials), p_color_index, 'owner', now())
  returning id into v_member;

  perform fulla.seed_defaults(v_household, p_locale);

  return jsonb_build_object('household_id', v_household, 'member_id', v_member,
                            'config', fulla.config_bundle(v_household));
end;
$$;

/*
 * Turns a household that has lived only on a phone into a shared one.
 *
 * Everything keeps the id the phone gave it, so the phone's rows need no
 * renumbering: its transactions are pushed afterwards through the ordinary
 * sync, pointing at ids that now exist here. The caller becomes the owner and
 * claims the member the phone called "me"; the other members arrive as members
 * without an account, ready to be claimed through invites.
 *
 * Idempotent: calling it again for a household the caller already owns
 * returns the same ids, so an interrupted upload can simply be retried.
 *
 * Payload:
 *   {"household": {"id", "name", "currency", "locale", "period_start_day",
 *                  "income_shift_day", "week_start"},
 *    "me_member_id": "...",
 *    "members": [...], "accounts": [...], "categories": [...],
 *    "custom_fields": [...], "budgets": [...], "recurring_rules": [...],
 *    "categorization_rules": [...], "import_profiles": [...]}
 */
create function public.fulla_household_create_from_local(p_payload jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_uid uuid := fulla.require_user();
  v_h jsonb := p_payload -> 'household';
  v_household uuid := fulla.json_uuid(p_payload -> 'household', 'id');
  v_me uuid := fulla.json_uuid(p_payload, 'me_member_id');
  v_existing fulla.members;
  x jsonb;
begin
  if v_household is null or v_me is null then
    perform fulla.invalid('`household.id` and `me_member_id` are required.');
  end if;

  if exists (select 1 from fulla.households h where h.id = v_household) then
    select * into v_existing from fulla.members m
     where m.household_id = v_household and m.user_id = v_uid and m.status = 'active';
    if v_existing.id is null then
      perform fulla.fail(409, 'already_exists', 'A household with this id already exists.');
    end if;
    return jsonb_build_object('household_id', v_household, 'member_id', v_existing.id,
                              'config', fulla.config_bundle(v_household));
  end if;

  perform fulla.check_household_fields(v_h ->> 'name', v_h ->> 'currency', v_h ->> 'locale');
  insert into fulla.households (id, name, currency, locale, period_start_day, income_shift_day, week_start, created_by)
  values (v_household, btrim(v_h ->> 'name'), upper(v_h ->> 'currency'), v_h ->> 'locale',
          coalesce((v_h ->> 'period_start_day')::smallint, 1),
          (v_h ->> 'income_shift_day')::smallint,
          coalesce((v_h ->> 'week_start')::smallint, 1),
          v_uid);

  if not exists (select 1 from jsonb_array_elements(coalesce(p_payload -> 'members', '[]')) m
                  where fulla.try_uuid(m ->> 'id') = v_me) then
    perform fulla.invalid('`me_member_id` must be one of `members`.');
  end if;
  for x in select value from jsonb_array_elements(coalesce(p_payload -> 'members', '[]')) loop
    perform fulla.check_person(x ->> 'display_name', x ->> 'initials', coalesce((x ->> 'color_index')::integer, 0));
    insert into fulla.members (id, household_id, display_name, initials, color_index, status)
    values (fulla.json_uuid(x, 'id'), v_household, btrim(x ->> 'display_name'), btrim(x ->> 'initials'),
            coalesce((x ->> 'color_index')::smallint, 0),
            case when x ->> 'status' = 'archived' then 'archived' else 'active' end);
  end loop;
  update fulla.members
     set user_id = v_uid, role = 'owner', status = 'active', joined_at = now()
   where household_id = v_household and id = v_me;

  if fulla.active_member_count(v_household) > (select h.member_limit from fulla.households h where h.id = v_household) then
    perform fulla.fail(409, 'household_full', 'More members than the household allows.');
  end if;

  for x in select value from jsonb_array_elements(coalesce(p_payload -> 'accounts', '[]')) loop
    perform fulla.save_account(v_household, x);
  end loop;
  -- Parents before children.
  for x in select value from jsonb_array_elements(coalesce(p_payload -> 'categories', '[]'))
            order by (value ->> 'parent_id') is not null loop
    perform fulla.save_category(v_household, x);
  end loop;
  for x in select value from jsonb_array_elements(coalesce(p_payload -> 'custom_fields', '[]')) loop
    perform fulla.save_field(v_household, x);
  end loop;
  for x in select value from jsonb_array_elements(coalesce(p_payload -> 'budgets', '[]')) loop
    perform fulla.save_budget(v_household, x);
  end loop;
  for x in select value from jsonb_array_elements(coalesce(p_payload -> 'recurring_rules', '[]')) loop
    perform fulla.save_recurring(v_household, x);
  end loop;
  for x in select value from jsonb_array_elements(coalesce(p_payload -> 'categorization_rules', '[]')) loop
    perform fulla.save_rule(v_household, x);
  end loop;
  for x in select value from jsonb_array_elements(coalesce(p_payload -> 'import_profiles', '[]')) loop
    perform fulla.save_import_profile(v_household, x);
  end loop;

  return jsonb_build_object('household_id', v_household, 'member_id', v_me,
                            'config', fulla.config_bundle(v_household));
end;
$$;

/*
 * Household settings. Admins may change the name, locale, period rules and
 * first day of the week; only the owner may change the currency or the member
 * limit. Changing the currency converts nothing: amounts keep their numbers.
 */
create function public.fulla_household_update(p_household_id uuid, p_patch jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_me fulla.members := fulla.require_member(p_household_id, 'admin');
  v_h fulla.households;
begin
  if jsonb_typeof(p_patch) <> 'object' then
    perform fulla.invalid('The patch must be an object.');
  end if;
  if (p_patch ? 'currency' or p_patch ? 'member_limit') and v_me.role <> 'owner' then
    perform fulla.fail(403, 'forbidden_role', 'Only the owner can change the currency or the member limit.');
  end if;
  select * into v_h from fulla.households h where h.id = p_household_id for update;

  if p_patch ? 'name' then v_h.name := btrim(p_patch ->> 'name'); end if;
  if p_patch ? 'currency' then v_h.currency := upper(p_patch ->> 'currency'); end if;
  if p_patch ? 'locale' then v_h.locale := p_patch ->> 'locale'; end if;
  perform fulla.check_household_fields(v_h.name, v_h.currency, v_h.locale);

  if p_patch ? 'period_start_day' then v_h.period_start_day := coalesce((p_patch ->> 'period_start_day')::smallint, 1); end if;
  if p_patch ? 'income_shift_day' then v_h.income_shift_day := (p_patch ->> 'income_shift_day')::smallint; end if;
  if p_patch ? 'week_start' then v_h.week_start := (p_patch ->> 'week_start')::smallint; end if;
  if p_patch ? 'member_limit' then v_h.member_limit := (p_patch ->> 'member_limit')::smallint; end if;

  if v_h.period_start_day not between 1 and 28 then
    perform fulla.invalid('The period must start on a day from 1 to 28.');
  end if;
  if v_h.income_shift_day is not null and v_h.income_shift_day not between 1 and 31 then
    perform fulla.invalid('The income shift day must be from 1 to 31.');
  end if;
  if v_h.period_start_day > 1 and v_h.income_shift_day is not null then
    perform fulla.invalid('A period that starts mid-month and shifted income cannot both be on.');
  end if;
  if v_h.week_start not between 1 and 7 then
    perform fulla.invalid('The week starts on a day from 1 (Monday) to 7 (Sunday).');
  end if;
  if v_h.member_limit not between 1 and 50 or v_h.member_limit < fulla.active_member_count(p_household_id) then
    perform fulla.invalid('The member limit must be 1 to 50 and not below the current number of members.');
  end if;

  update fulla.households h
     set name = v_h.name, currency = v_h.currency, locale = v_h.locale,
         period_start_day = v_h.period_start_day, income_shift_day = v_h.income_shift_day,
         week_start = v_h.week_start, member_limit = v_h.member_limit
   where h.id = p_household_id;

  return fulla.config_bundle(p_household_id);
end;
$$;

-- ── invites ──────────────────────────────────────────────────────────────────

/*
 * Admins invite members; only the owner invites admins. An invite may be
 * earmarked for a member without an account (p_claim_member_id): whoever
 * accepts it becomes that member and inherits their history.
 */
create function public.fulla_invite_create(
  p_household_id uuid, p_role text default 'member', p_claim_member_id uuid default null, p_ttl_hours integer default 72
) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_me fulla.members := fulla.require_member(p_household_id, case when p_role = 'admin' then 'owner' else 'admin' end);
  v_code text;
  v_expires timestamptz;
begin
  if p_role not in ('admin', 'member') then
    perform fulla.invalid('An invite is for the admin or member role.');
  end if;
  if p_ttl_hours is null or p_ttl_hours not between 1 and 720 then
    perform fulla.invalid('An invite lasts from 1 hour to 30 days.');
  end if;
  if p_claim_member_id is not null and not exists (
    select 1 from fulla.members m
     where m.household_id = p_household_id and m.id = p_claim_member_id
       and m.user_id is null and m.status = 'active'
  ) then
    perform fulla.invalid('Only an active member without an account can be claimed.');
  end if;

  v_code := fulla.new_invite_code();
  v_expires := now() + make_interval(hours => p_ttl_hours);
  insert into fulla.invites (code, household_id, role, claim_member_id, created_by_member_id, expires_at)
  values (v_code, p_household_id, p_role, p_claim_member_id, v_me.id, v_expires);

  return jsonb_build_object('code', v_code, 'expires_at', fulla.iso(v_expires),
                            'role', p_role, 'claim_member_id', p_claim_member_id);
end;
$$;

create function public.fulla_invite_list(p_household_id uuid) returns jsonb
language plpgsql stable security definer
set search_path = ''
as $$
begin
  perform fulla.require_member(p_household_id, 'admin');
  return coalesce((
    select jsonb_agg(jsonb_build_object(
             'code', i.code, 'role', i.role, 'claim_member_id', i.claim_member_id,
             'created_by_member_id', i.created_by_member_id,
             'created_at', fulla.iso(i.created_at), 'expires_at', fulla.iso(i.expires_at))
           order by i.created_at)
      from fulla.invites i
     where i.household_id = p_household_id
       and i.used_at is null and i.revoked_at is null and i.expires_at > now()
  ), '[]'::jsonb);
end;
$$;

create function public.fulla_invite_revoke(p_household_id uuid, p_code text) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
begin
  perform fulla.require_member(p_household_id, 'admin');
  update fulla.invites i set revoked_at = now()
   where i.household_id = p_household_id and i.code = fulla.normalize_invite_code(p_code)
     and i.used_at is null and i.revoked_at is null;
  if not found then
    perform fulla.fail(404, 'not_found', 'No such open invite.');
  end if;
  return jsonb_build_object('ok', true);
end;
$$;

/*
 * Accepts an invite.
 *
 * Unlike every other function, a bad code is answered with
 * {"ok": false, "error": "<code>"} rather than an HTTP error. A failed guess
 * has to be recorded to throttle the next one, and an exception would roll the
 * record back along with everything else. After 10 failed guesses in an hour
 * a user gets too_many_attempts (429) until the hour has passed.
 *
 * Errors: invite_invalid (unknown or revoked), invite_expired, invite_used,
 * already_member, household_full.
 */
create function public.fulla_invite_accept(
  p_code text, p_display_name text, p_initials text, p_color_index integer default 0
) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_uid uuid := fulla.require_user();
  v_invite fulla.invites;
  v_member fulla.members;
  v_error text;
  v_member_id uuid;
begin
  if (select count(*) from fulla.invite_attempts a where a.user_id = v_uid and a.at > now() - interval '1 hour') >= 10 then
    perform fulla.fail(429, 'too_many_attempts', 'Too many invite codes tried. Wait an hour and try again.');
  end if;

  select * into v_invite from fulla.invites i where i.code = fulla.normalize_invite_code(p_code) for update;
  v_error := case
    when v_invite.code is null or v_invite.revoked_at is not null then 'invite_invalid'
    when v_invite.used_at is not null then 'invite_used'
    when v_invite.expires_at <= now() then 'invite_expired'
  end;
  if v_error is not null then
    insert into fulla.invite_attempts (user_id) values (v_uid);
    delete from fulla.invite_attempts a where a.user_id = v_uid and a.at < now() - interval '1 day';
    return jsonb_build_object('ok', false, 'error', v_error);
  end if;

  if exists (select 1 from fulla.members m
              where m.household_id = v_invite.household_id and m.user_id = v_uid and m.status = 'active') then
    return jsonb_build_object('ok', false, 'error', 'already_member');
  end if;
  perform fulla.check_person(p_display_name, p_initials, coalesce(p_color_index, 0));

  if v_invite.claim_member_id is not null then
    select * into v_member from fulla.members m
     where m.household_id = v_invite.household_id and m.id = v_invite.claim_member_id for update;
    if v_member.user_id is not null or v_member.status <> 'active' then
      return jsonb_build_object('ok', false, 'error', 'invite_invalid');
    end if;
    -- A person who was once in the household and is claiming a placeholder
    -- keeps a single member row: the placeholder.
    update fulla.members m set user_id = null, role = 'member', status = 'archived'
     where m.household_id = v_invite.household_id and m.user_id = v_uid;
    update fulla.members m
       set user_id = v_uid, role = v_invite.role, joined_at = now(),
           display_name = btrim(p_display_name), initials = btrim(p_initials), color_index = coalesce(p_color_index, 0)
     where m.id = v_member.id;
    v_member_id := v_member.id;
  else
    if fulla.active_member_count(v_invite.household_id)
       >= (select h.member_limit from fulla.households h where h.id = v_invite.household_id) then
      return jsonb_build_object('ok', false, 'error', 'household_full');
    end if;
    select * into v_member from fulla.members m
     where m.household_id = v_invite.household_id and m.user_id = v_uid;
    if v_member.id is not null then
      -- Someone who left, or was removed, coming back keeps their history.
      update fulla.members m
         set status = 'active', role = v_invite.role, joined_at = now(),
             display_name = btrim(p_display_name), initials = btrim(p_initials), color_index = coalesce(p_color_index, 0)
       where m.id = v_member.id;
      v_member_id := v_member.id;
    else
      insert into fulla.members (household_id, user_id, display_name, initials, color_index, role, joined_at)
      values (v_invite.household_id, v_uid, btrim(p_display_name), btrim(p_initials), coalesce(p_color_index, 0),
              v_invite.role, now())
      returning id into v_member_id;
    end if;
  end if;

  update fulla.invites i set used_by_user_id = v_uid, used_at = now() where i.code = v_invite.code;

  return jsonb_build_object('ok', true, 'household_id', v_invite.household_id, 'member_id', v_member_id,
                            'config', fulla.config_bundle(v_invite.household_id));
end;
$$;

-- ── members ──────────────────────────────────────────────────────────────────

-- A person who is part of the household without an account of their own.
create function public.fulla_member_create_virtual(p_household_id uuid, p_member jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_id uuid;
begin
  perform fulla.require_member(p_household_id, 'admin');
  perform fulla.check_person(p_member ->> 'display_name', p_member ->> 'initials',
                             coalesce((p_member ->> 'color_index')::integer, 0));
  if fulla.active_member_count(p_household_id)
     >= (select h.member_limit from fulla.households h where h.id = p_household_id) then
    perform fulla.fail(409, 'household_full', 'The household has reached its member limit.');
  end if;
  v_id := coalesce(fulla.json_uuid(p_member, 'id'), gen_random_uuid());
  if exists (select 1 from fulla.members m where m.id = v_id) then
    perform fulla.fail(409, 'already_exists', 'A member with this id already exists.');
  end if;
  insert into fulla.members (id, household_id, display_name, initials, color_index)
  values (v_id, p_household_id, btrim(p_member ->> 'display_name'), btrim(p_member ->> 'initials'),
          coalesce((p_member ->> 'color_index')::smallint, 0));
  return fulla.config_bundle(p_household_id);
end;
$$;

-- Anyone may change their own name, initials and colour; admins anyone's.
create function public.fulla_member_update(p_household_id uuid, p_member_id uuid, p_patch jsonb) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_me fulla.members := fulla.require_member(p_household_id, 'member');
  v_target fulla.members;
begin
  select * into v_target from fulla.members m where m.household_id = p_household_id and m.id = p_member_id;
  if v_target.id is null then
    perform fulla.fail(404, 'not_found', 'No such member.');
  end if;
  if v_target.id <> v_me.id and fulla.role_rank(v_me.role) < fulla.role_rank('admin') then
    perform fulla.fail(403, 'forbidden_role', 'Only admins can change other members.');
  end if;
  if p_patch ? 'display_name' then v_target.display_name := btrim(p_patch ->> 'display_name'); end if;
  if p_patch ? 'initials' then v_target.initials := btrim(p_patch ->> 'initials'); end if;
  if p_patch ? 'color_index' then v_target.color_index := (p_patch ->> 'color_index')::smallint; end if;
  perform fulla.check_person(v_target.display_name, v_target.initials, v_target.color_index);
  update fulla.members m
     set display_name = v_target.display_name, initials = v_target.initials, color_index = v_target.color_index
   where m.id = v_target.id;
  return fulla.config_bundle(p_household_id);
end;
$$;

create function public.fulla_member_set_role(p_household_id uuid, p_member_id uuid, p_role text) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_me fulla.members := fulla.require_member(p_household_id, 'owner');
begin
  if p_role not in ('admin', 'member') then
    perform fulla.invalid('The role must be admin or member. To hand over the household, transfer ownership.');
  end if;
  if p_member_id = v_me.id then
    perform fulla.fail(409, 'last_owner', 'Transfer ownership before changing your own role.');
  end if;
  update fulla.members m set role = p_role
   where m.household_id = p_household_id and m.id = p_member_id and m.status = 'active' and m.user_id is not null;
  if not found then
    perform fulla.fail(404, 'not_found', 'No such active member with an account.');
  end if;
  return fulla.config_bundle(p_household_id);
end;
$$;

/*
 * Removes a member. They lose access at once; their history stays, because it
 * is the household's history too. The owner can remove anyone else; an admin
 * can remove members but not admins or the owner.
 */
create function public.fulla_member_remove(p_household_id uuid, p_member_id uuid) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_me fulla.members := fulla.require_member(p_household_id, 'admin');
  v_target fulla.members;
begin
  select * into v_target from fulla.members m
   where m.household_id = p_household_id and m.id = p_member_id and m.status = 'active';
  if v_target.id is null then
    perform fulla.fail(404, 'not_found', 'No such active member.');
  end if;
  if v_target.id = v_me.id then
    perform fulla.invalid('To leave the household yourself, use leave.');
  end if;
  if v_target.role = 'owner' or (v_target.role = 'admin' and v_me.role <> 'owner') then
    perform fulla.fail(403, 'forbidden_role', 'You cannot remove this member.');
  end if;
  update fulla.members m
     set status = case when m.user_id is null then 'archived' else 'removed' end
   where m.id = v_target.id;
  update fulla.invites i set revoked_at = now()
   where i.household_id = p_household_id and i.claim_member_id = v_target.id and i.used_at is null and i.revoked_at is null;
  return fulla.config_bundle(p_household_id);
end;
$$;

create function public.fulla_member_leave(p_household_id uuid) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_me fulla.members := fulla.require_member(p_household_id, 'member');
begin
  if v_me.role = 'owner' then
    perform fulla.fail(409, 'last_owner', 'Transfer ownership before leaving.');
  end if;
  update fulla.members m set status = 'removed' where m.id = v_me.id;
  return jsonb_build_object('ok', true);
end;
$$;

create function public.fulla_owner_transfer(p_household_id uuid, p_member_id uuid) returns jsonb
language plpgsql volatile security definer
set search_path = ''
as $$
declare
  v_me fulla.members := fulla.require_member(p_household_id, 'owner');
begin
  if not exists (select 1 from fulla.members m
                  where m.household_id = p_household_id and m.id = p_member_id and m.id <> v_me.id
                    and m.status = 'active' and m.user_id is not null) then
    perform fulla.fail(404, 'not_found', 'Ownership can only go to another active member with an account.');
  end if;
  update fulla.members m set role = 'admin' where m.id = v_me.id;
  update fulla.members m set role = 'owner' where m.id = p_member_id;
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
