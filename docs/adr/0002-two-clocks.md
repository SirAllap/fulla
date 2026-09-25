# 2. Edit time decides conflicts; a server sequence pages the sync

**Status:** accepted

## Context

Phones edit offline. The time a person made an edit is known only to their
phone, and it can be hours before the server hears about it.

## Decision

Two fields:

- `client_updated_at`, the editing phone's clock, decides which version of a
  row wins (last write wins).
- `server_seq`, drawn from a sequence on every write, is the cursor pulls page
  on.

## Consequences

A row written offline and pushed late gets a fresh `server_seq`, so every
other phone's next pull includes it. Paging on edit time instead would put it
behind their cursors permanently. The test `a row written offline hours ago is
not stepped over` holds this.
