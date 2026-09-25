# 6. The API is functions; tables are not exposed

**Status:** accepted

## Context

Supabase exposes tables in API schemas through PostgREST, guarded by row level
security. Role checks (owner, admin, member), cross-row validation and the
sync protocol do not fit table-level policies well, and every exposed table is
more attack surface.

## Decision

Tables live in a `fulla` schema that PostgREST does not expose. `anon` and
`authenticated` have no privilege on it. The API is a list of
`public.fulla_*` functions, each `security definer` with an empty
`search_path`, each checking membership and role first. Execute is revoked
from `anon` (Supabase grants it by default). Row level security stays enabled
underneath as a second barrier.

## Consequences

The function list is the whole attack surface and is tested as such: the
suite checks every function's privileges, `security definer` and
`search_path`, and calls every household function as a stranger.
