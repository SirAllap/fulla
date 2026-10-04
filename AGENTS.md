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

What a person types in a text field is read by `MoneyParser.parseTyped`, never by
`parse` with the language's style: a phone's decimal key follows the phone's
region and not the app's language, so `12.50` in an app set to Spanish was
read as 1.250 until it was. Text that is not an amount is said so and holds
Save back; it never keeps the old value as if it had been typed. (`parse`
stays for files, where the style is stated.) An account's opening balance is
the one amount that can be below zero: a credit card, an overdraft.

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

The period rule (`fulla.period_of`, and `fulla.anchored_period_of` when the
salary starts each period), split allocation (`fulla.split_shares`),
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
New means new to the phone: an upsert without a base that meets a stored row
(two phones wrote the same recurring occurrence) follows the rule too. A
phone-only household uploads without `money_mode` and sets it once its history
is pushed (`LocalHousehold.forUpload`, `Ledger.finishSharing`), or its old rows
would arrive as new ones and be rewritten.

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

### Fixed costs write themselves, on every phone, in every mode

A recurring item with `auto_create` is written by the phone, never by the
server, and in every household: `Ledger.generateRecurring` runs for each one
on the phone, phone-only ones included. (An earlier version ran it only for
shared households, inside the sync, so a phone-only household's fixed costs
never wrote themselves: nobody noticed until people relied on them.) It never
waits for a network. It runs when the app opens or comes back
(`MainActivity.onStart`), at midnight while the app stays open, every six
hours in the background (`RecurringWorker`), after every sync, and as soon as
a fixed cost is saved (`Ledger.upsert`). Reading what is held, deciding and
writing happen in one Room transaction, so two callers never write one
occurrence twice; the id (uuid5 of rule and date) is the same on every
phone, so the sync merges what two phones wrote. `a phone-only household
writes its fixed costs too, with nothing owed to a server` and `a phone opened
every day and one opened once after a month write the same rows` hold it.

What it writes is decided in `RecurringPlanner`, three guards deep: nothing
for a day that has not come; nothing for an occurrence somebody already wrote
down by hand (`Coverage.byHand`: same kind and category, an amount within
5 %, a date within 3 days, each row standing for one occurrence); and a rule
that has never written starts with the current period, then carries on from
the first thing it wrote, so what was left out the first time is not written
the second (`what was left out the first time is not written the second`).
The forecast asks `Coverage.byHand` too, so what the planner leaves alone it
counts as paid, never as still to come: the two cannot disagree. A new rule
begins with the current period, a changed one from today on
(`RecurringPlanner.startFor`). An occurrence whose day has come and which
nothing wrote shows as overdue, with "Apply" (`Ledger.applyRecurring`).

### A figure compares like with like

The analysis is read as fact, so a figure must not compare unequal things. `FiguresAddUpTest` runs 240 made-up
households and checks that every figure agrees with every other one that a person could add up with a calculator
(spent everywhere, categories add up to spent, fixed + variable = spent, forecast = spent + fixed to come + everyday,
balances add up to zero, accounts hold what they opened with plus what moved, percentages of a chart add up to 100).
A new figure gets its identity there. The rules it enforces:

- A period that is still running is held against the *same days* of the earlier ones (`Analytics.trends` with
  `today`, `PeriodReport.previousSpentMinor` with `partial`), never against whole months: ten days against a
  month always read as "much less".
- "Usual" is the average of the up to three earlier periods *that have anything written down*, so one month of history
  is that month and a month the app was not used is not a month of zero.
- A day-to-day pace is learnt only from periods that had day-to-day spending; a period of fixed costs only is not a
  pace of zero. Without any, this period's own pace stands in (a week in, five rows at least, only what is marked
  variable, one big purchase held to five usual rows) with a wider range.
- What is left to spend per day counts today (`PeriodForecast.daysToGo`), like a trip's daily figure, and a trip not
  started yet spreads what is left of its budget, not the whole of it (something may be booked ahead).
- Days without spending count only days that are over: today is not a day without spending until it ends.
- The daily average and the days' shares are of everyday (variable) spending: the fixed costs are their own figures.
- What is "left" is called left while a period runs (`PeriodReport.partial`), and saved only once it is over.
- Left and over are never both shown and never negative (`BudgetStatus`, a trip's totals).
- What already is a recurring item is not suggested again as something that looks recurring.
- A percentage is rounded the same wherever it is shown, and the percentages of one chart are split by the largest
  remainder (`Percent.split`) so they add up to exactly 100.
- A row the server refused is in no figure (`SyncEngine.counted`), so every phone adds up the same; the overview says
  how many there are. A row written down with a future date is committed money, never "spent so far" and never a pace.
- Screens do no arithmetic on money: a sum, a left, an over or a share is worked out in `core` (with its test) and
  drawn. Every figure, its definition and its source are in `docs/figures.md`.
- One amount reads the same on every platform (`MoneyFormatterTest`), the day moves on by itself at midnight on every
  screen that works from it (`rememberToday` in the app, a tick in the web app), and the own-pace range of the
  forecast is backtested (`ForecastTest`).

### Migrations

Applied in filename order, each once, recorded in `fulla.schema_migrations`.
After the first release, a released migration is never edited: add a new
file. Before the first release they may still be rewritten.

Every migration that changes the schema bumps the constant returned by
`public.fulla_schema_version()` (`0016_schema_version.sql`), in the same
statement that changes the schema. Self-hosted Supabase has no release
channel to notice a stale database on its own; the app compares this number
against `EXPECTED_SCHEMA_VERSION` (`client`) and nudges the owner in Settings
when the backend is behind. A migration that does not bump it is a migration
the nudge cannot see.

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
refresh and coming back to the app all call it. It moves rows and nothing
else: a feature must never depend on the household being shared. The first
version of fixed costs wrote them from inside the sync, so a phone-only
household never got them; `AppContainer.generateRecurring` is where that work
lives now, and `syncAll` merely calls it first. Anything that has to happen
on its own (on a day, when the app opens) gets its own entry point like that
one, for every household, and a test with a phone-only household.

Four tabs (Add, Overview, History, Balances); everything else hangs off the
gear. Screens are built from `ui/components/Pieces.kt`; if two screens need
the same thing, it is a component. The overview's jar (`HeroJar`) is the only
element allowed visual weight, and its levels come from `core`'s `Hero`, its
surfaces from `Liquid`. Colours come from `core`'s `design/Colors.kt`, never
from a hex value in a screen.

The APK is built by CI only (no Android SDK is assumed locally). A debug
build of every push to `main` is published to the `latest-debug` prerelease.

Every Room version bump ships a migration test.

## Release notes

`docs/releases/<version>.md` is the text of the GitHub release, and the update
sheet in the app shows it. Write it in Spanish and English, Spanish first:
a `## Español` section and a `## English` section (`ReleaseNotes.forLanguage`
shows the one in the phone's language, English when there is none for it).
Plain Markdown only: bold, italic, `code`, `-` bullets and headings are
rendered (`ReleaseNotes.parse`); anything else shows as text.
