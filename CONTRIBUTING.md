# Contributing to Fulla

Thanks for considering it. A few things matter more here than in most
projects, because Fulla handles people's money and data.

## Before you start

- Read [AGENTS.md](AGENTS.md). It lists the rules the code relies on and the
  things that look simplifiable and are not.
- For anything larger than a small fix, open an issue first so we can agree on
  the approach.

## Setting up

You need Node.js 20 or newer and a PostgreSQL installation (15 or newer). You
do not need a running PostgreSQL server or a Supabase project: the test suite
starts its own throwaway cluster.

```sh
npm run hooks:install   # scans every commit for secrets
npm run test:db
```

## Rules for changes

**Money is integers.** Amounts are whole minor units (cents, yen, fils) in a
64-bit integer, everywhere. Never a float.

**Nothing is deleted.** Rows are archived or marked deleted, and a trigger
refuses `DELETE`. A deletion must be able to travel to other phones.

**Migrations are append-only once released.** Never edit a migration that has
been part of a release; add a new file. `create or replace function` in a new
file is how you change a function.

**Every API function** is `security definer`, has `set search_path = ''`,
starts with `fulla.require_member()` (or `fulla.require_user()`), and has
execute revoked from `public` and `anon`. The test suite checks all of this
for every function automatically.

**Rules that exist twice agree.** The period rule, split allocation and member
balances are implemented both in SQL and in the app. Their test vectors live
in `testdata/vectors/`, and both implementations must pass them.

**Tests go through the API as a user.** In `supabase/tests/run.js`, use
`rpc(db, user, ...)` or `db.as(user, ...)` for anything a phone could do.
`db.admin()` bypasses row level security and proves nothing about access.

## Test data

Use invented data only: people are Alice, Bob, Carol and so on at
`example.com`; merchants are things like `GROCERY STORE 01`; dates are in
2030; amounts are made up. Never paste a real bank statement, a real name or a
real amount into a test, a fixture, an issue or a commit message, not even
your own.

## Commits and pull requests

- Small, focused commits with a clear message (`feat(db): …`, `fix(sync): …`,
  `test: …`, `docs: …`).
- All tests green and `npm run leakcheck` clean.
- Describe what changed and why in the pull request.

## Translations

Translations are welcome. The app's strings live in Android resource files
(`values/strings.xml` for English); add a `values-xx/` folder for your
language.

## Licence

By contributing you agree that your contribution is licensed under the
GPL-3.0-or-later, the same as the project.
