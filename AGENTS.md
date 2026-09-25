# Working on Fulla

Orientation for whoever picks this up next, person or agent. Read it before
changing sync, money, the schema or access control. `CLAUDE.md` is a symlink
to this file.

## What exists

- `supabase/migrations/`: the whole backend. Tables in a private `fulla`
  schema that PostgREST never exposes; the API is `public.fulla_*` functions.
- `supabase/tests/`: a harness that boots a throwaway PostgreSQL, applies
  `shim.sql` (what Supabase provides) and then the real migrations, unmodified,
  plus the suite in `run.js`.
- `supabase/scripts/`: `bundle.js` (builds `dist/setup.sql`) and `migrate.js`.
- `testdata/`: defaults (categories, currencies) and test vectors shared with
  the app.
- `tools/leakcheck/`: the secret and personal-data scanner.

- `core/`: pure Kotlin on the JVM, no Android. Money, the model, the period
  rule, splits and balances, recurring schedules, custom fields, the sync
  engine, analytics, the keypad, importers (CSV with a column mapping, OFX,
  QIF), permissions and demo data. Every decision the app makes lives here,
  with tests; the Android app will only call it.

- `client/`: the phone's side of the protocol, still plain Kotlin on the JVM.
  The wire format, the Supabase transport (auth and `rpc`, written by hand,
  refusing any host but the project's), the sync loop over a storage
  interface, the rules for local edits, local-mode households and recurring
  generation. Tested against a mocked HTTP engine and an in-memory store.
- `app/`: the Android app. Storage (Room), background work, screens. It
  implements `client`'s `SyncStore` and calls the rest; it decides nothing
  that a test in `core` or `client` could hold instead.

## Commands

```sh
npm run test:db                # all backend tests (needs PostgreSQL installed, not running)
node supabase/tests/run.js xyz # only tests whose name contains "xyz"
npm run leakcheck              # scan the working tree
npm run i18n                   # every language has every string
npm run leakcheck:history      # scan every commit, message and identity
npm run db:bundle              # write dist/setup.sql
./gradlew :core:test           # domain tests (needs a JDK 17+, no Android SDK)
./gradlew :core:koverVerify    # coverage of core must stay at 90 % or more
./gradlew :client:test         # transport and sync loop (JDK only)
```

Everything must be green before a commit.

## The rules the code depends on

These look simplifiable. They are not. Each has a test.

### Money is integers

Amounts are `bigint` minor units of the household's currency, always positive;
the transaction's `kind` gives the direction. Never a float anywhere. Minor
units per currency come from `testdata/defaults/currencies.json` (0 for JPY, 3
for BHD, 2 for most).

### Two clocks, two fields

`client_updated_at` is when a person made an edit, by their phone's clock. It
decides which version wins a collision.

`server_seq` is assigned by the database on every write. It is the cursor a
pull pages on.

They cannot be the same field. A phone that was offline all morning pushes
edits stamped hours ago; paging on edit time would put them behind every other
phone's cursor and they would never be pulled. If you find yourself writing
`since = max(client_updated_at)`, stop.

### The cursor moves on a pull, never on a push

A push returns results only. The cursor a phone stores is the highest
`server_seq` among the rows a pull returned. Any value minted for a push sits
above rows other phones wrote since this one last pulled, and adopting it
would skip those rows forever. Test: `rows another member wrote are not lost
when this phone pushes before pulling`.

### server_seq is taken under a per-household lock

A sequence hands out numbers at write time, not commit time. Two concurrent
writers can take 10 and 11 and commit as 11, then 10; a pull in between moves
past 11 and never sees 10. The trigger `fulla.assign_server_seq` takes a
transaction-scoped advisory lock per household before `nextval`, so numbers
are taken in commit order. `a pull between two concurrent writers never skips
the slower one` fails if the lock is removed.

### A delete is not an edit

A delete is applied without comparing clocks: two phones' clocks are never in
step, and a deletion that loses to a clock is a row alive on one phone and
gone on another. Nothing is ever physically deleted: `status = 'deleted'` is
a tombstone, and triggers refuse `DELETE` on every table except
`invite_attempts`.

The one exception is deleting an account (`fulla_account_delete`): a
household where that account was the only one is erased by
`fulla.erase_household`, which names the household in a transaction-local
setting the delete guard checks. Nothing else sets it; `erasing one household
never unlocks deleting rows of another` holds the guard.

### An absent custom field keeps its value

`extras` is merged, not replaced: a key that is absent keeps the stored value,
an explicit `null` clears it. A phone that hasn't heard of a new field yet
must not blank the value another phone set.

### Access goes through functions only

`anon` and `authenticated` hold no privilege on the `fulla` schema. Every API
function is `security definer`, pins `search_path = ''`, qualifies every name,
and starts with `fulla.require_member(household, min_role)` or
`fulla.require_user()`. Row level security is on underneath as a second
barrier. When you add a function, the suite's security tests check all of this
automatically and will fail until it is right. Supabase grants EXECUTE on new
`public` functions to `anon` by default; the shim reproduces that, and each
migration ends by revoking it.

### Rules that exist twice agree

The period rule (`fulla.period_of`), split allocation (`fulla.split_shares`),
member balances and name normalisation also exist in the app. Their vectors
are in `testdata/vectors/`; both sides must pass them. Change one side, change
the other.

The shared pot is one of them. In a household with `money_mode = 'shared'` a
new expense or refund is stored split to its payer alone, and a new settlement
is refused: `SharedPot.forNew` in `core/.../split/SharedPot.kt` on the phone
(through `Edits.create`, so recurring items and imports follow it too) and
`fulla.shared_pot_for_new` in `0011_money_mode.sql`, called where
`fulla_sync_push` inserts, so a phone that has not heard of the switch still
creates no debt. Both pass `testdata/vectors/shared_pot.json`. It applies to
new rows only: an edit keeps its split, and nothing written before a switch is
rewritten (`an edit of a row written before the shared pot keeps its split`).

### The simulation is the sync's real test

`ten phones converge whatever the order` in `core/.../SyncTest.kt` runs ten
phones with clocks up to two hours out against a fake server that behaves like
`fulla_sync_push` and `fulla_sync_pull`, for 300 seeds. It found two real bugs
while it was being written: an edit refused as older left the phone with its own
version, and a delete of something already deleted left the phone with its own
tombstone. Both are why a push answer carries `server_transaction`. If you
change the sync, change the fake server to match the SQL first, then run the
simulation with more seeds before trusting a pass.

`client/.../SyncerTest.kt` runs the same kind of simulation through the real
`Syncer`, with the store contract the app implements. A store write carries a
guard, the stamp the sync read: a person can edit while a sync is in flight,
and the answer to the older version must not overwrite that edit.

An edit is stamped with the phone's clock or one millisecond after the version
it edits, whichever is later (`SyncEngine.stampFor`), so a phone whose clock is
behind cannot lose an edit to the very version it was looking at.

### Migrations

Applied in filename order, each once, recorded in `fulla.schema_migrations`.
After the first release, a released migration is never edited: add a new
file. Before the first release they may still be rewritten.

## Keeping the repository clean

This repository is public: tests, fixtures and docs use invented data only
(Alice, Bob, Carol at example.com; `GROCERY STORE 01`; 2030; made-up
amounts). The pre-commit hook and CI run `tools/leakcheck`; its private
denylist lives outside the repository (`~/.config/fulla/denylist.txt` locally,
the `LEAKCHECK_DENYLIST` secret in CI) and is never committed.

## The app

`AppContainer` builds everything once; there is no DI framework (ADR 0011).
`Ledger` is the only thing screens write through: every edit goes through
`client`'s `Edits`, so stamps and sync states are decided in one tested place.
`AppContainer.syncAll` is the only way a sync starts: the worker, pull to
refresh and coming back to the app all call it.

Four tabs (Add, Overview, History, Balances); everything else hangs off the
gear. Screens are built from `ui/components/Pieces.kt`; if two screens need
the same thing, it is a component. The overview's jar (`HeroJar`) is the only
element allowed visual weight, and its levels come from `core`'s `Hero`, its
surfaces from `Liquid`. Colours come from `core`'s `design/Colors.kt`, never
from a hex value in a screen.

The APK is built by CI only (no Android SDK is assumed locally). A debug
build of every push to `main` is published to the `latest-debug` prerelease.
