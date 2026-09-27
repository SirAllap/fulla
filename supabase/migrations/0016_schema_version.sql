-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- Self-hosted Supabase has no release channel: the app ships, the database
-- does not follow unless someone remembers to paste the new setup.sql by
-- hand. The app cannot tell "not installed yet" from "installed but stale"
-- without asking the backend something. `fulla_schema_version` answers with
-- a constant baked into this migration, one integer higher each time a
-- migration changes the schema; the app compares it against the constant it
-- shipped with (`EXPECTED_SCHEMA_VERSION` in `client`) and nudges the owner
-- only when the backend is behind.
--
-- The constant is the running migration count, this file included, so
-- bumping it is "the next migration's number", not a separate number to
-- remember. Every future schema-changing migration must raise it, in the
-- same statement that changes the schema.
--
-- Authenticated only, like every other `fulla_*` function including
-- `fulla_ping` (0005_household.sql): there is no anonymous caller of the
-- API, and this one carries no household id to check `require_member`
-- against, but it still asks nothing of an unauthenticated request.

create function public.fulla_schema_version() returns integer
language sql immutable security definer
set search_path = ''
as $$
  select 16;
$$;

-- ── grants ───────────────────────────────────────────────────────────────────

revoke all on function public.fulla_schema_version() from public, anon;
grant execute on function public.fulla_schema_version() to authenticated;
