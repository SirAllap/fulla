# 4. Nothing is deleted

**Status:** accepted

## Context

A deletion made on one phone must reach the others. A row that no longer
exists cannot carry that news.

## Decision

Transactions are tombstoned (`status = 'deleted'`); structure is archived.
Triggers refuse `DELETE` on every household table. A deletion is applied
without comparing clocks, and on the phone an incoming deletion beats a local
row that has no unsent edit, whatever the clocks say.

## Consequences

- Deleted rows stay out of every total but remain in the database.
- A deletion can be undone by a newer edit.
- Two phones' clocks disagreeing can never resurrect a deleted row on one of
  them.
