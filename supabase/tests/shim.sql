-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- A stand-in for what a Supabase project provides before any of our
-- migrations run, so the real migration files can be applied unmodified.
--
-- The rule for this file: faithful, not convenient. If Supabase behaves a
-- certain way, this does too. In particular it reproduces Supabase's default
-- privileges on the `public` schema, which grant EXECUTE on every new function
-- to `anon` and `authenticated`. A migration that forgets to revoke that is a
-- migration that exposes a function to the whole internet, and the test suite
-- must be able to see it.

-- Roles are cluster-wide, so they outlive the per-test databases. Idempotent.
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then
    create role anon nologin noinherit;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then
    create role authenticated nologin noinherit;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'service_role') then
    create role service_role nologin noinherit bypassrls;
  end if;
end
$$;

-- Supabase keeps extensions in their own schema.
create schema if not exists extensions;
create extension if not exists pgcrypto with schema extensions;

grant usage on schema public to anon, authenticated, service_role;
grant usage on schema extensions to anon, authenticated, service_role;

-- Supabase's defaults for objects created in `public` by the migration role.
alter default privileges in schema public grant all on tables to anon, authenticated, service_role;
alter default privileges in schema public grant all on sequences to anon, authenticated, service_role;
alter default privileges in schema public grant execute on functions to anon, authenticated, service_role;

-- The auth schema. The real `auth.users` has many more columns; these are the
-- ones anything in this repository reads.
create schema auth;

create table auth.users (
  id         uuid primary key default gen_random_uuid(),
  email      text unique,
  created_at timestamptz not null default now()
);

-- The same definitions Supabase ships: the caller's identity comes from the
-- JWT claims PostgREST puts in `request.jwt.claims`. A request without a JWT
-- has no `sub`, so `auth.uid()` is null.
create function auth.uid() returns uuid
language sql stable
as $$
  select nullif(
    coalesce(
      current_setting('request.jwt.claim.sub', true),
      current_setting('request.jwt.claims', true)::jsonb ->> 'sub'
    ),
    ''
  )::uuid;
$$;

create function auth.role() returns text
language sql stable
as $$
  select nullif(
    coalesce(
      current_setting('request.jwt.claim.role', true),
      current_setting('request.jwt.claims', true)::jsonb ->> 'role'
    ),
    ''
  )::text;
$$;

grant usage on schema auth to anon, authenticated, service_role;
grant execute on function auth.uid() to anon, authenticated, service_role;
grant execute on function auth.role() to anon, authenticated, service_role;
