# 9. Splits use the largest remainder method

**Status:** accepted

## Context

1.00 split three ways is not three equal amounts of minor units. The leftover
unit has to go somewhere, and the phone and the database must put it in the
same place, or balances disagree.

## Decision

Each participant gets the floor of their exact share; the remaining units go
one each to the largest fractional remainders, ties broken by member id in
byte order. The participants are stored in the row, so later changes to the
household do not change past splits. The rule is implemented in SQL and in the
app, and both pass `testdata/vectors/allocate.json`.

## Consequences

Parts always add up to the amount, no part is more than one unit from its
exact share, and the result does not depend on the order members were listed.
