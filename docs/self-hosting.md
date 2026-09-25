# Setting up a shared household

You only need this to share a household between phones. On a single phone,
Fulla works without any of it.

A shared household lives in a Supabase project that **you** own. Fulla's
authors never see it, and nobody else can reach it without an account you let
in. The free Supabase plan is enough for a household.

## The quick way: let the app do it

1. Sign up at [supabase.com](https://supabase.com) (free).
2. Open **Account → Access Tokens**
   ([supabase.com/dashboard/account/tokens](https://supabase.com/dashboard/account/tokens)),
   generate a token with any name, and copy it.
3. In Fulla: **Shared → paste the token → Set up my project.**

The app then, through Supabase's Management API (`api.supabase.com`):

- creates a project called `fulla` in your first organization, in the region
  group of your phone's time zone, with a random database password it never
  shows or keeps (nothing in Fulla needs it; reset it in the dashboard if you
  ever want it);
- waits for the project to start, which takes a minute or two;
- installs the database: the same `setup.sql` as below, shipped inside the app;
- turns on sign-up without email confirmation, because Supabase's built-in
  email only reaches your own team's addresses, so the rest of the household
  would never receive a confirmation. Signing up gives nobody access to
  anything: a household is reachable only through an invite;
- reads the project's address and public (anon) key, and connects.

The token is used for those calls and never stored. You can delete it on the
tokens page as soon as the app says it is done. If it stops halfway (no
connection, the project slow to start), tapping again carries on with the
same project instead of making a second one.

The free plan allows two active projects per account. If both are in use,
pause one, or set up by hand below.

## A new phone, or the app reinstalled

Nothing is lost: the household lives in your Supabase project. To connect
again, create a fresh token at
[supabase.com/dashboard/account/tokens](https://supabase.com/dashboard/account/tokens)
and paste it in **Shared**. The app finds your project called `fulla` and
connects to it, without creating another or touching your data (running the
setup again is safe). If your project has another name, it lists your projects
to choose from; a project Fulla did not create keeps its own sign-in settings.
Then choose **I already have an account** and sign in with your email and
password.

People who joined with an invite do not need a token: they scan a new invite
from someone already in the household, or sign in again on the same project.

## By hand

### 1. Create a Supabase project

1. Sign up at [supabase.com](https://supabase.com) and create a new project.
2. Pick the region closest to you.
3. Choose a strong database password and keep it somewhere safe. The app never
   needs it; you need it only for backups and command-line tools.

### 2. Install the database

Choose one of these. They produce exactly the same result, and both record
what they applied in `fulla.schema_migrations`, so you can switch between them.

**A. SQL Editor (nothing to install).** Download `setup.sql` from the latest
[release](../../releases). In your project, open **SQL Editor**, paste the
whole file and press **Run**. It is safe to run again, and running a newer
`setup.sql` later upgrades an existing installation.

**B. Migration script.** With `psql` installed and the connection string from
**Project Settings → Database**:

```sh
DATABASE_URL='postgresql://…' npm run db:migrate
```

### 3. Configure sign-in

In **Authentication → Sign In / Providers**, make sure **Email** is enabled
and turn **Confirm email** off (`mailer_autoconfirm` in Supabase's own terms).
Supabase's built-in email only delivers to the addresses of your own Supabase
team, so anyone else in the household would wait for a confirmation that never
comes. Keep it on only if you have set up your own SMTP server
(**Authentication → Emails**).

This is why the app-driven setup in "The quick way" above turns the same
setting on for you: it is what lets a household run without anyone owning a
mail server. It is not an open door — signing up to your project gives
nobody access to any household's data. A household is reachable only through
an invite, so an account with no invite sees nothing. Once everyone who needs
access has joined, turn off **Allow new users to sign up** in the same
screen — optional, but tidier (see "Close the door" below).

### 4. Copy two values for the app

Both are in your project's **Project Settings → API Keys**
([supabase.com/dashboard/project/_/settings/api-keys](https://supabase.com/dashboard/project/_/settings/api-keys)
asks which project, then opens that page):

- **Project URL.** Open your project; the browser's address is
  `supabase.com/dashboard/project/<20 letters>`. The URL is
  `https://<those 20 letters>.supabase.co`, like
  `https://abcdefghijklmnopqrst.supabase.co`.
- **Public key.** Either the **publishable** key (starts with
  `sb_publishable_`) or, under **Legacy API keys**, the **anon public** key
  (a long one starting with `eyJ`). Fulla accepts both.

Or skip both: **Use a token instead** on the same screen does it for you.

The anon key is not a secret: it identifies the project, and on its own it can
do nothing in Fulla's database. **Never** give the app, or anyone, the
`service_role` key or a `sb_secret_` key.

### 5. Create the household in the app

Install Fulla, choose **Create a shared household**, paste the URL and the key,
create your account, and name the household. If you have been using Fulla on
this phone only, choose **Connect a backend** in Settings instead: everything
you already have is uploaded.

## Invite the others

In **Settings → Members**, create an invite. It is a QR code and a ten-letter
code, valid for three days and usable once. The other person installs Fulla,
chooses **Join a household** and scans it.

An invite can be earmarked for a member who has been in the household without
an account, such as a child who is now old enough for the app. Whoever accepts
it becomes that member and keeps their history.

## Close the door

When everyone is in, go to **Authentication → Sign In / Providers** and turn
off **Allow new users to sign up**. This is optional: someone who signs up to
your project without an invite sees nothing. But it is tidier.

## Keeping it running

- **Paused projects.** Supabase pauses free projects after a period without
  use. The app will tell you; restore the project from the Supabase dashboard
  and everything carries on, with nothing lost. Changes made on the phones in
  the meantime are uploaded when it comes back.
- **Backups.** Supabase keeps its own backups on paid plans. On any plan you
  can take one yourself with `pg_dump` and the connection string. Keep backups
  somewhere private: they contain the whole household's finances.
- **Upgrades.** A new version of Fulla may come with a new `setup.sql`. Run it
  the same way as the first time; it applies only what is new.
