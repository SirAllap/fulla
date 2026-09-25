# Security

Fulla stores people's financial data, so security reports are taken seriously.

## Reporting a vulnerability

Please **do not open a public issue**. Report it privately through GitHub:
**Security → Report a vulnerability** on this repository. That creates a
private advisory only the maintainers can see.

Include what you found, how to reproduce it, and what an attacker could do
with it. You will get an answer as soon as possible, and credit in the fix if
you want it.

## Scope

In scope:

- the database schema, row level security and API functions in `supabase/`;
- the Android app: storage of credentials and data on the device, network
  behaviour, the invite and sign-in flows;
- anything that lets one household see or change another household's data,
  or lets a member exceed their role.
- the updater: finding, downloading and installing releases from GitHub;
- the setup from a Supabase access token.

Out of scope: the security of Supabase itself, and of any individual user's
Supabase project configuration. Those are reported to Supabase or are the
project owner's to fix.

## What Fulla promises

- Each household's data is reachable only by its active members, enforced in
  the database, not only in the app.
- `anon` can call nothing; no table is exposed through the API.
- The app contacts only the user's own Supabase project, plus Supabase's
  `api.supabase.com` while setting a project up from a token, and GitHub
  while checking for or downloading an update (which can be turned off).
- A token pasted for setup is used for those calls and never stored.
- The app never sends analytics, telemetry or crash reports anywhere.

A way to break any of these is a vulnerability.
