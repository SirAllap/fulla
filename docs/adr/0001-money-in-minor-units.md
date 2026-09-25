# 1. Money is integer minor units

**Status:** accepted

## Context

A household ledger accumulates thousands of small amounts. Binary floating
point cannot represent most decimal amounts exactly, and sums drift by a unit
here and there. A finance app that disagrees with a bank statement by one cent
is broken.

## Decision

Every amount is a 64-bit integer count of the currency's minor unit (cents for
EUR, yen for JPY, fils for BHD), in the database, on the wire and in the app.
Transaction amounts are always positive; the kind gives the direction. Text is
converted to minor units in exactly one place, the app's parser, which rounds
half away from zero on the decimal text, never through a float.

## Consequences

- No rounding happens in the database at all.
- Currencies with 0 or 3 decimals work without special cases.
- Formatting for display is the app's job, per locale.
