# 3. The cursor advances on a pull, never on a push

**Status:** accepted

## Context

It is tempting to have a push return a stamp and let the phone skip
re-downloading its own rows.

## Decision

A push returns per-mutation results only. The only cursor a phone stores is
the highest `server_seq` among the rows a pull actually returned.

## Consequences

A stamp minted for a push is above rows other members wrote since this phone
last pulled. Storing it as the cursor would skip those rows, on every later
pull, forever: two phones would drift apart, each holding what it typed
itself. The cost of the rule is re-downloading one's own rows after a push,
which the merge applies as no-ops.
