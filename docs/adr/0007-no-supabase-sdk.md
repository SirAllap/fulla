# 7. No Supabase SDK in the app

**Status:** accepted

## Context

Fulla promises that the app talks to one host, the user's own project. Every
dependency is code that could open another connection.

## Decision

The app makes its few HTTP calls (sign-in, token refresh, RPC) by hand with
one HTTP client, restricted to the configured `*.supabase.co` host.

## Consequences

A few hundred lines of client code instead of a large dependency, and a
network promise that can be audited by reading them.
