# 11. The protocol lives in a JVM module; no dependency injection framework

**Status:** accepted

## Context

The Android module can only be compiled where an Android SDK exists, and
nothing in it runs without an emulator or Robolectric. The one class of bug
that matters most here, two phones silently disagreeing, comes from the glue
between the sync rules and the storage, which is exactly the part that is
hardest to test on Android.

## Decision

Everything between the domain (`core`) and the Android framework that does
not need Android lives in `client`, a plain Kotlin module: the wire format,
the Supabase transport, the sync loop, the rules for local edits, local-mode
households and recurring generation. The sync loop talks to storage through
an interface (`SyncStore`) whose contract, including the guarded writes, is
also implemented in memory for tests. The app implements it with Room.

The app builds its object graph by hand in `AppContainer`. It is a few dozen
lines; a framework would add a compiler plugin and generated code for no
behaviour a reader could not see directly.

## Consequences

`./gradlew :client:test` runs the transport against a mocked HTTP engine and
the sync loop against the store contract in seconds, on any machine with a
JDK. The Android code is left with storage, background work and screens.
A new dependency of the app is visible in one file.
