-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- Access control.
--
-- The model: `anon` and `authenticated` hold no privilege at all on the
-- `fulla` schema. Every read and write goes through a `public.fulla_*`
-- function that is SECURITY DEFINER and starts by calling
-- fulla.require_member(), which is where membership and roles are enforced.
--
-- Row level security is enabled underneath anyway, with read-only policies,
-- so that a privilege granted by mistake one day exposes a household's rows
-- to its own members and nobody else.

revoke all on schema fulla from public, anon, authenticated;
revoke all on all tables in schema fulla from public, anon, authenticated;
revoke all on all sequences in schema fulla from public, anon, authenticated;
revoke all on all functions in schema fulla from public, anon, authenticated;
alter default privileges in schema fulla revoke all on tables from public, anon, authenticated;
alter default privileges in schema fulla revoke all on functions from public, anon, authenticated;

-- ── errors ───────────────────────────────────────────────────────────────────

/*
 * Raises an error PostgREST turns into a real HTTP status: a SQLSTATE of the
 * form PTxyz becomes status xyz. The machine-readable code travels in DETAIL,
 * which PostgREST returns as `details`; the message is for people.
 */
create function fulla.fail(p_status integer, p_code text, p_message text) returns void
language plpgsql
as $$
begin
  raise exception using errcode = 'PT' || p_status::text, message = p_message, detail = p_code;
end;
$$;

-- For expressions: fails with validation_failed and never returns.
create function fulla.invalid(p_message text) returns void
language sql
as $$ select fulla.fail(400, 'validation_failed', p_message); $$;

-- ── membership ───────────────────────────────────────────────────────────────

create function fulla.role_rank(p_role text) returns integer
language sql immutable
as $$ select case p_role when 'owner' then 3 when 'admin' then 2 when 'member' then 1 else 0 end; $$;

create function fulla.is_active_member(p_household_id uuid) returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (
    select 1 from fulla.members m
     where m.household_id = p_household_id
       and m.user_id = auth.uid()
       and m.status = 'active'
  );
$$;

/*
 * The first line of every API function that touches a household. Returns the
 * caller's member row, or fails: not_member (403) if the caller has no active
 * membership, forbidden_role (403) if their role is below p_min_role.
 */
create function fulla.require_member(p_household_id uuid, p_min_role text default 'member')
returns fulla.members
language plpgsql stable security definer
set search_path = ''
as $$
declare
  v_me fulla.members;
begin
  if auth.uid() is null then
    perform fulla.fail(401, 'not_authenticated', 'Sign in first.');
  end if;
  select * into v_me
    from fulla.members m
   where m.household_id = p_household_id
     and m.user_id = auth.uid()
     and m.status = 'active';
  if v_me.id is null then
    perform fulla.fail(403, 'not_member', 'You are not a member of this household.');
  end if;
  if fulla.role_rank(v_me.role) < fulla.role_rank(p_min_role) then
    perform fulla.fail(403, 'forbidden_role', format('This needs the %s role or higher.', p_min_role));
  end if;
  return v_me;
end;
$$;

create function fulla.require_user() returns uuid
language plpgsql stable
as $$
begin
  if auth.uid() is null then
    perform fulla.fail(401, 'not_authenticated', 'Sign in first.');
  end if;
  return auth.uid();
end;
$$;

-- ── row level security ───────────────────────────────────────────────────────

do $$
declare
  t text;
begin
  foreach t in array array['households', 'members', 'invites', 'invite_attempts', 'accounts', 'categories',
                           'custom_fields', 'budgets', 'recurring_rules', 'categorization_rules',
                           'import_profiles', 'transactions']
  loop
    execute format('alter table fulla.%I enable row level security', t);
  end loop;

  foreach t in array array['members', 'invites', 'accounts', 'categories', 'custom_fields', 'budgets',
                           'recurring_rules', 'categorization_rules', 'import_profiles', 'transactions']
  loop
    execute format(
      'create policy %I on fulla.%I for select to authenticated using (fulla.is_active_member(household_id))',
      t || '_members_read', t);
  end loop;
end
$$;

create policy households_members_read on fulla.households
  for select to authenticated using (fulla.is_active_member(id));

revoke all on all functions in schema fulla from public, anon, authenticated;
