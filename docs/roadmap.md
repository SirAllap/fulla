# Roadmap

What is left, in order.

Decided: Fulla is free software (GPL-3.0-or-later) and costs nothing. Nobody
hosts anyone's data: a household keeps its own Supabase project (free tier),
or stays on one phone. Distribution: signed APKs on GitHub Releases only,
never Google Play; the app updates itself from there. The hosted cloud and Google sign-in stay in the code,
switched off; they only turn on in a build given those secrets.

## Done: joining a household with one scan

Setting up a second phone must take no typing beyond a name and an account.
The invite is a link (`fulla://join?u=…&k=…&c=…`) carrying the project URL,
the anon key and a one-time code, shown as a QR code:

- Members → Invite shows the link as a **QR code**, large, with the code in
  words under it for reading aloud.
- The welcome screen gets **"Scan an invite"** as a first-class choice, with
  the camera opening straight away. Scanning fills in the project, and the
  only things left to type are an email, a password and a name.
- Opening the link from a message does the same.
- "Share this household" (phone-only → shared) ends by showing that QR, so
  the natural next step after sharing is the other phone scanning it.
- Still open: taking even the Supabase setup out of the owner's hands (a
  guided, copy-free path), because people do not paste keys. See below.

A household that lives on one phone only cannot be joined: there is no server
for the second phone to talk to. The scan flow must say so plainly and offer
to share the household first.

## Done: people and the way out

- People: rename, make admin or member, remove, hand over the household,
  leave; invite someone without an account to take their place; list and
  revoke open invites.
- Export every transaction to CSV (History → ⋮), readable by any program and
  by Fulla's own importer, to the cent.

## Done: fields, rules, saved mappings, backups

- Custom fields of every type, from settings to the entry sheet to History.
- Categorisation rules, made while importing (pick a line's category, tick
  "remember"), paused and resumed in settings.
- Saved CSV mappings: a bank's file is mapped once.
- Backup to a file and restore, for phone-only households especially.
- Insights (Overview → ⋮): period by period, against the usual, who paid,
  totals by custom field, charges that look recurring, days without spending.
- Budgets for one particular month, and copying last month's.
- Recurring items of any schedule (daily, weekly, monthly, yearly), expense
  or income, editable and pausable.
- Account deletion from the app (Play requires it): households only that
  person used are erased; in the others their history stays and the
  membership is detached; an owner must hand over first.

## Done: languages

English, Spanish, French, German, Italian and Portuguese: every screen, and
the categories and accounts a new household starts with (the same JSON on
the phone and in the database). `npm run i18n` fails when a language misses a
string or a placeholder, and CI runs it. Android 13+ lets people choose the
app's language on its own. Screenshots in English and Spanish come from the
Screenshots workflow (the `screenshots` branch).

## Done: Fulla's own cloud and Google sign-in

Decided: Fulla hosts sync itself, on one Supabase project; users never see
Supabase. A build carries the project's address and Google's client ID from
CI secrets (docs/hosting.md). The welcome screen leads with "Continue with
Google"; email and a phone-only mode stay as alternatives; signing in on a
new phone brings back the households the account already has. A build
without the secrets keeps the bring-your-own-project flow.

## Done: importing another app's history

A CSV from another money app, not only from a bank: a column that says in or
out (for files whose amounts are all positive), and category, paid-by and
account columns matched to the household's own by name, accents and case
ignored. Categories the household lacks are created with the import, with ids
derived from their names, so the same file on two phones makes one category.
A file with an ordinary header in any of the six languages maps itself
(columns, date format, decimal separator, which values mean income); every
guess is a chip that can be changed.

A line whose category is the name of one of the household's accounts is
money changing pockets (cash drawn, cash paid back in) and comes in as a
transfer, not as spending: ledgers that file withdrawals under a "cash"
category would otherwise count every cash purchase twice. A fixed/variable
column is read too. Appearance has a per-app language row on Android 13+.

## Done: in-app updates

Fulla is distributed only as signed APKs on GitHub Releases, never through
Google Play, so it finds and installs its own updates:

- `core`'s `Versions` parses and compares release tags (`v1.2.3`), ignoring
  pre-release suffixes and treating `0.0.0` as "no real version, never an
  update" — a debug build's own version, so it never offers itself an update.
- `client`'s `UpdateCheck` asks `api.github.com/repos/SirAllap/fulla/releases/latest`,
  picks the release's `.apk` asset (never the `.aab`), and downloads it with
  the same hand-written, host-checked pattern as `ProjectSetup`: redirects are
  followed only to GitHub's own hosts, and the download's SHA-256 is verified
  against the digest GitHub provides when there is one.
- The app checks on `MainActivity.onStart`, throttled to once every 12 hours
  and stored in DataStore so the throttle and the pending update both survive
  a restart, skipped on a debug build (different signing key, can never
  install over a release build) and behind a Settings › About switch, on by
  default.
- A pending update shows as a dot on the Settings gear and a highlighted row
  at the top of the Settings index; either opens a sheet with the version,
  the release notes, the size, and an "Update now" button that downloads with
  a progress readout and installs through `PackageInstaller`, asking for the
  "install unknown apps" permission first if it is not already granted.
- `release.yml`'s `versionCode` comes from the tag
  (`major*10000 + minor*100 + patch`) rather than the CI run number, so it
  survives the repository being recreated one day.

## Then

- A promotional video for the README (motion design, the app's own screens).
- A reminder to save a backup after a few weeks without one.
- A household selector, for people in more than one household.
- A "your server" link or QR code in Settings, to reconnect a new phone
  without a Supabase token.

## Business model

Decided: none. Fulla is free and open source. Hosting other people's
financial data would bring fixed costs (a paid Supabase plan, email, backups)
and the duties of a data controller under the GDPR, for little money. A
donations link (GitHub Sponsors or Ko-fi) costs nothing and can come later.

## Observations

Things noticed while building, to decide on rather than forget.

- **A phone-only household has no backup** unless someone saves one:
  Settings → Backup writes the whole household to a file, and the welcome
  screen restores it. Still missing: a gentle reminder after a few weeks
  without a saved backup.
- **An invite opened while already in a household is ignored.** Joining a
  second household needs the household selector (v1.x); until then, say so
  instead of doing nothing.
- **Joining still means creating an account.** Supabase can send magic links
  or sign people in anonymously and let them add an email later; either would
  remove the password step. Anonymous accounts would need a way to recover
  them before they are a good default.
- **Setting up Supabase is the hard part for most people.** A guided path
  could create the tables through Supabase's Management API with a personal
  access token, instead of pasting `setup.sql`. That is a second host
  (`api.supabase.com`), used once, during setup only; it has to be stated as
  plainly as the update check.
- **An invite QR code is valid for three days and works once.** A household
  of several people needs one per person; the invite screen could keep
  showing a fresh one after each use.
- **"Looks like it repeats" in Insights only lists what it finds.** One tap
  to turn a found charge into a recurring item would close the loop.
- **Money on screen is formatted with java.text,** which groups every locale
  by thousands. Locales that group differently (en-IN) need android.icu, with
  the same sign rules.
- **The app has three Robolectric tests.** The Ledger (edits, connect, forget)
  and the settings changes deserve their own, against an in-memory Room.
- **Demo data is Spanish or English by the phone's language,** and uses the
  phone's currency as the household's. That is right for a first look;
  worth keeping in mind for screenshots.

### Sync without holding anyone's data (to think about)

Three ways for phones to sync without the seller reading anyone's finances:

1. **Bring your own Supabase** (what exists): no data, no cost, but almost
   nobody will create a project and paste keys. Keep as an advanced option.
2. **End-to-end encrypted sync (favoured).** A server Fulla runs stores and
   orders opaque, encrypted blobs; only the household's phones hold the key,
   which travels inside the invite QR code and never reaches the server. The
   server keeps `server_seq`, the per-household lock and the cursor rules;
   validation and merging move entirely to the phones, where the sync rules
   already live and are tested. "Not even we can see your money" becomes a
   true sentence. Needs a recovery path for a lost key (a recovery code
   written down once, or the key in the phone's own backup).
3. **Local Wi-Fi sync, no server**: free and private, but only while both
   phones are at home. A possible free extra, not the main sync.

Encrypted data is still personal data under the GDPR, but the practical
risk drops sharply when the holder cannot read it; confirm with an adviser.

### Ads (decided: not at launch, maybe later)

The free single-person tier launches without ads. Adding them later stays
possible, under these conditions, so the decision can be made with real
numbers:

- One discreet native ad on one screen that is not about entering money
  (e.g. at the end of History), never on Add or on the jar, never
  full-screen, never in the paid tiers.
- Ads mean the AdMob SDK: a third party that collects device identifiers,
  a GDPR consent prompt (Google's UMP), "shares data with advertisers" in
  the Play Data safety form, and finance ads that are often loans or
  crypto. Each of those costs trust, and trust is what a finance app sells.
- Rough value: a single-person user sees ~5 screens a day; at Spanish
  native eCPMs of ~1 € that is ~0.15 € per active user per month. It only
  pays at thousands of daily users, and it lowers conversion to paid.
- Alternative without ads: a small one-off "Fulla Plus" for one person
  (e.g. 3.99 €: bank import, insights, extra palettes and icons), so single
  users who love the app have something to buy.
