# Sync

Each phone keeps a full copy of its household in a local database and works
entirely from it. Sync moves transactions between that copy and the
household's Supabase project in the background. Nothing waits on the network.

Structure (members, accounts, categories, fields, budgets, recurring items,
rules, profiles) is not synced row by row: it changes only online, through the
structure functions, and comes down whole whenever `config_version` moves.

## Two clocks

| Field | Set by | Used for |
|---|---|---|
| `client_updated_at` | the editing phone's clock | deciding which version of a row wins |
| `server_seq` | the database, on every write | the cursor a pull pages on |

They are deliberately separate. A phone that was offline for hours pushes
edits stamped hours ago. If pulls paged on edit time, those rows would land
behind every other phone's cursor and never be seen.

## Push

```json
{"p_household_id": "…",
 "p_mutations": [
   {"mutation_id": "…", "type": "upsert", "client_id": "device id",
    "client_updated_at": "2030-01-15T12:00:00.000Z",
    "base_client_updated_at": "2030-01-15T11:00:00.000Z",
    "transaction": { …the whole row… }}]}
```

- At most 100 mutations per call. Each runs in its own savepoint: one bad
  mutation fails alone.
- `delete` sends the row with `status: "deleted"`. It is applied without
  comparing clocks: a deletion is not a field edit, and two phones' clocks are
  never in step. Deleting a row that is already deleted answers with the
  stored tombstone as `server_transaction`, which the phone adopts.
- `upsert` of an existing row applies only if its `client_updated_at` is not
  older than the stored one. If it is older, the stored row stays and the
  result carries `conflict: {winner: "server", overwritten: [...]}` so the phone
  can tell its user what was lost, and `server_transaction`, the stored row,
  which the phone adopts immediately: that row may already be behind the
  phone's cursor, and no later pull would bring it.
- The phone stamps an edit with its clock, or one millisecond after the
  version it edited if its clock is behind that version. An edit made after
  seeing a version is newer than it, whatever the clocks say.
- If `base_client_updated_at` (the version the edit started from) is not the
  stored version, somebody else edited in between. The newer edit still wins,
  and the result carries `conflict: {winner: "client", ...}`.
- Custom fields in `extras` are merged: an absent key keeps its stored value,
  `null` clears it. Unknown keys are dropped with a warning.
- `trip_id` follows the same rule: absent keeps the stored trip, `null`
  clears it, a uuid is checked and set. It only ever survives on an `expense`
  or a `refund`; a kind that can't have one drops it in silence rather than
  refusing the row, so a phone that never learned about trips can still edit
  a tripped row without losing the push.

Result, one per mutation:

```json
{"mutation_id", "transaction_id", "ok", "applied", "note"?, "conflict"?, "warnings": [], "error"?: {"code", "message"}}
```

`ok: true` means settled: the phone marks the row synced, whether or not it was
applied. `ok: false` means rejected: the phone keeps the row, shows the error,
and does not retry until the user changes it.

**A push never returns a cursor.** See below.

## Pull

```json
{"p_household_id": "…", "p_since": 12345, "p_config_version": 17, "p_limit": 500}
→ {"transactions": [...], "cursor": 12400, "has_more": false,
   "config_version": 18, "config": {...}?, "server_time": "…"}
```

- Returns rows with `server_seq > p_since`, in `server_seq` order.
- `cursor` is the highest `server_seq` among the rows returned (or `p_since` if
  none). The phone stores it only after applying the page, and repeats while
  `has_more`.
- `config` is included when `p_config_version` is not the current version. The
  phone applies it before the rows in the same response.

## The cursor rules

1. The cursor advances on a pull, never on a push. A value minted for a push
   sits above rows other phones wrote since this phone last pulled; storing it
   would skip them forever.
2. The cursor only ever takes a value the server returned from a pull, and
   only moves forward.
3. `server_seq` is drawn under a per-household advisory lock, so numbers are
   taken in commit order. Without it, two concurrent writers can commit their
   numbers out of order, and a pull between the two commits moves past a row
   that is not yet visible.

## Merging on the phone

For each row a pull returns:

1. Not held locally: store it.
2. A remote deletion wins over a local row with no unsent edit, whatever the
   clocks say.
3. Otherwise the later `client_updated_at` wins. If the local row wins and was
   already marked synced, it is queued to be pushed again; otherwise its win
   would exist on this phone only.
4. If the remote row wins over a local unsent edit and they differ, the user
   gets a conflict note listing the fields that changed.

A sync is push, then pull (all pages), then one more push if the merge queued
anything. Exactly one extra push, never a loop.

## Recurring items

Occurrences are generated on the phones. Each occurrence's id is a UUIDv5 of
the rule id and the occurrence date, so two phones generating the same
occurrence produce the same row, and a unique index on
`(rule, occurrence date)` backs that up in the database. An occurrence that was
deleted keeps its id and is never generated again.
