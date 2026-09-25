# 5. server_seq is drawn under a per-household lock

**Status:** accepted

## Context

A sequence gives out numbers when a row is written, not when its transaction
commits. Two concurrent writers in one household can take 10 and 11 and
commit 11 first. A pull between the two commits sees 11, stores it as its
cursor, and never asks for 10. The row is lost to that phone for good. The
more members a household has, the likelier this is.

## Decision

The trigger that assigns `server_seq` first takes a transaction-scoped
advisory lock keyed on the household. A second writer in the same household
waits for the first to commit before drawing a number, so numbers are drawn
in commit order within a household.

## Consequences

Writes within one household are serialised. With batches of up to 100 rows
and a handful of people this costs nothing noticeable. Households do not wait
for each other. The test `a pull between two concurrent writers never skips
the slower one` fails if the lock is removed.
