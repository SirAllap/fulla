# 10. Structure changes online; transactions work offline

**Status:** accepted

## Context

Transactions are many, entered anywhere, and independent of each other.
Structure (accounts, categories, fields, members, budgets) is small, changed
rarely, and changes to it interact: two people renaming a category offline in
two different ways has no good automatic answer.

## Decision

In a shared household, structure is changed through request/response functions
that need a connection and return the whole configuration. A
`config_version` counter moves on every structural change; a pull carries the
whole configuration when the phone's version is behind, before any rows. On a
phone with no backend, everything is local and nothing needs a connection.

## Consequences

The offline sync protocol only has to handle one kind of row. A phone that is
offline cannot change structure until it reconnects; the app says so.
