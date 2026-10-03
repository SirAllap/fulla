# The web app

Fulla in a browser, for an iPhone or anything else that has one. It is the same Fulla: the
rules (periods, splits, fixed costs, the backup format) are `core` and `client`, compiled to
JavaScript, and the web app decides nothing they could.

- **Where**: `https://sirallap.github.io/fulla/app/`, built by `.github/workflows/pages.yml`
  on every push to `main` that touches it.
- **Install**: on an iPhone, Safari › Share › Add to Home Screen. Added, it opens like an app,
  works with no connection (`sw.js`) and the browser is far less likely to clear its data.
- **Where the data lives**: in the browser's IndexedDB, as the text of a backup file
  (`Ledger.kt`). Nothing is sent anywhere: the page's Content Security Policy forbids any
  request but to its own site. It asks the browser for persistent storage, but a browser may
  still clear a site's data, and an iPhone's Safari does so after weeks without a visit: so
  Settings › Backup says when no backup has been saved for a month, and saving one goes through
  the share sheet (Files, iCloud, AirDrop).
- **A backup moves both ways**: the file the web app saves is the one the Android app saves
  (`Backup.kt`), and each restores the other's.

## What it does, and what it does not (yet)

Local households only. Add (expense, income, refund), Overview (the period, where it went,
fixed costs and what is left to spend), History (search, filter, edit, delete with undo),
fixed costs, categories, accounts, backup and restore, CSV, six languages, light and dark.

Not yet: sharing a household through Supabase (the `client` transport is common code and
ready), trips, custom fields, budgets, importing a bank statement, salary-started months,
people and balances.

## Building and testing

```sh
./gradlew :web:jsBrowserDistribution            # web/build/dist/js/productionExecutable
cd web/e2e && npm ci && node run.mjs            # Chromium, iPhone screen, the page's own CSP
FULLA_SHOTS=/tmp/shots node run.mjs             # and keep a screenshot of each step
```

The strings come from the Android resources (`tools/web/strings.mjs`), so a language added
there is a language here.
