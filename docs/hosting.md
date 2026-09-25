# Running Fulla's cloud

How the owner of Fulla sets up the one Supabase project every copy of the app
syncs through, and Google sign-in on top of it. Users never see any of this:
they tap "Continue with Google" or scan an invite.

A build without these settings still works: it asks people for their own
Supabase project, as in [self-hosting.md](self-hosting.md).

## 1. The Supabase project

1. supabase.com → New project. Region: an EU one (the data is European
   households'). A strong database password, kept in a password manager.
2. SQL Editor → paste `setup.sql` (from a release, or `npm run db:bundle`) →
   Run. Later versions: the same file again; it applies only what is new.
3. For real users: the **Pro plan**. Free projects pause after a week without
   requests, and a paused project means no phone syncs.
4. Authentication → Emails: set up custom SMTP (for example Resend) before
   launch. The built-in sender allows only a few emails an hour; email
   sign-up and password resets need it. Google sign-in sends no email.

## 2. Google sign-in

In Google Cloud Console (console.cloud.google.com), one project for Fulla:

1. **OAuth consent screen**: External, app name Fulla, support email, the
   privacy policy URL. Scopes: only `openid`, `email`, `profile`.
2. **Credentials → Create OAuth client ID → Web application.** No redirect
   URIs needed. Copy its **client ID** and **client secret**.
3. **Credentials → Create OAuth client ID → Android**, once per signing key:
   - package `io.github.sirallap.fulla.debug`, SHA-1 of the debug key (printed
     by CI in the "Place the debug key" step of the Android job);
   - package `io.github.sirallap.fulla`, SHA-1 of the upload key.
4. In Supabase: Authentication → Sign In / Providers → **Google**: enable,
   paste the web client ID and secret, and leave "Skip nonce checks" **off**
   (Fulla sends a nonce).

## 3. Into the build

GitHub → the repository → Settings → Secrets and variables → Actions → New
repository secret, three of them:

| Secret | Value |
|---|---|
| `FULLA_PROJECT_URL` | `https://<project>.supabase.co` |
| `FULLA_ANON_KEY` | the project's anon (public) key |
| `FULLA_GOOGLE_CLIENT_ID` | the **web** client ID from step 2.2 |

The next build (every push to `main`, and every release tag) carries them.
None of them is secret in the strict sense (they ship inside the app), but
keeping them out of the repository keeps the project's address out of the
code and lets a different server be used per build.

## What this makes you responsible for

The households' data is in your project. The privacy policy must say so,
account deletion is already in the app, backups of the project are yours to
arrange (Pro includes daily ones), and GDPR obligations apply.
