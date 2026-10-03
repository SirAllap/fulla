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

## What it does, and what it does not

The screens are the Android app's, built from its own measures (`ui/theme/Type.kt`,
`ui/components/Pieces.kt`), its icons (Material Outlined) and its words (the same strings, six
languages). Add (kinds, categories and subcategories, trips, custom fields, fixed, "starts the
month"), Overview (the jar, the fixed costs' bars, where it went), Analysis, History, Balances,
and everything under the gear: households, household, people, categories, custom fields,
accounts, budgets, trips, fixed costs (ends, financing), importing a statement (CSV, OFX, QIF),
rules, backup, appearance, sync, about; and the getting-started guide.

**Several people**: a household can be shared through Supabase and joined with an invite (link,
code or QR), from the browser or from the Android app. The project's address and key are the
Android build's (the CI secrets `FULLA_PROJECT_URL` and `FULLA_ANON_KEY`), so people only sign
in; without them the page does what the Android app does: a guide (create a Supabase account, create an access token, paste it) and Fulla creates the project, installs `setup.sql` (served beside the page) and connects; "I already have a project" takes the URL and key instead. The token is used for these calls only (the CSP allows `api.supabase.com` for this) and never kept. If Supabase refuses calls from a browser page, the guide says so and the manual way remains. An iPhone syncs while the
page is open. `web/e2e` shares a household between two browsers through the project's real
migrations in a throwaway PostgreSQL behind a fake Supabase (`web/e2e/fake-supabase.cjs`).

Not in a browser: the biometric lock, widgets, background work, Google sign-in. Words only the
web uses live in `web/strings` (merged by `tools/web/strings.mjs`); the Android resources are
not touched.

## Building and testing

```sh
./gradlew :web:jsBrowserDistribution            # web/build/dist/js/productionExecutable
cd web/e2e && npm ci && node run.mjs            # Chromium, iPhone screen, the page's own CSP
FULLA_SHOTS=/tmp/shots node run.mjs             # and keep a screenshot of each step
```

The strings come from the Android resources (`tools/web/strings.mjs`), so a language added
there is a language here.
