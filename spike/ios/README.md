# iOS packaging spike

Question this answers: can a GitHub Actions macOS runner produce an unsigned
`.ipa` that SideStore/AltStore can sideload, built from a Compose
Multiplatform screen? See `.github/workflows/ios-spike.yml`.

This folder is self-contained and outside the root Gradle build (the root
`settings.gradle.kts` only includes `:core`, `:client`, `:app`). It does not
touch `core/`, `client/` or `app/`, and nothing here is meant to ship.

- `shared/`: a Kotlin Multiplatform module (`iosArm64`, `iosSimulatorArm64`)
  with one Compose screen: title, a build/version string, and a button that
  counts taps.
- `iosApp/`: an Xcode project shaped like the JetBrains KMP wizard template.
  `DEVELOPMENT_TEAM` is empty and code signing is off
  (`CODE_SIGNING_ALLOWED=NO`) -- the whole point is proving the unsigned path
  works. Bundle id `io.github.sirallap.fulla.spike`, display name
  "Fulla test", iOS 15 deployment target.

No Mac was used to write this; nothing here has been built locally. CI
(`macos-15`) is the only place it has actually compiled.
