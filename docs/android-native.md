# BKE DNA Logger — Android-native architecture

Android is a first-class capture runtime, not a secondary export target.

## Locked platform ownership

```text
Windows / macOS                         Android
      .NET                               Kotlin
       |                                   |
 browser/native adapters                 GeckoView
       |                                   |
       +----------- DNA CONTRACT ----------+
                   |
          evidence / graph / SQLite
                   |
             verified .dna
```

Windows and macOS remain .NET implementations. Android is a native Kotlin implementation. Cross-platform compatibility is defined by the DNA wire protocol, evidence semantics, identifiers, hashing rules, graph semantics, SQLite meaning, and `.dna` formats — not by requiring shared source code or a shared runtime.

GeckoView is therefore consumed directly from Kotlin. The project deliberately does not generate a managed C# binding for Mozilla's full Java/Kotlin API surface.

## Android runtime direction

```text
BKE DNA Android app (Kotlin)
  -> GeckoView
  -> bundled privileged WebExtension
  -> shared MAIN-world response interceptor
  -> GeckoView native messaging
  -> Kotlin native ingress
  -> Kotlin DNA evidence/storage implementation
  -> SQLite
  -> verified .dna
```

## Current Kotlin foundation gate

This stack establishes:

- a native Gradle/Kotlin Android application shell;
- the exact stable Mozilla artifact `org.mozilla.geckoview:geckoview-arm64-v8a:154.0.20260824154132` consumed directly from Kotlin;
- compile-time references to `GeckoRuntime`, `GeckoSession`, `GeckoView`, and `WebExtension` without a C# binding generator;
- private app-local DNA storage under `FilesDir/dna/captures`;
- a Kotlin wire ingress that accepts the same `capture_start`, `capture_chunk`, `capture_end`, and `dom_witness` message types as desktop;
- the existing GeckoView-compatible built-in WebExtension assets;
- the canonical desktop `extension/main-interceptor.js` copied into the Android APK at build time rather than forked;
- CI that builds the Android APK with the pinned Gradle/AGP/Kotlin toolchain.

## GeckoView runtime gate immediately after this one

The next stacked gate must implement:

1. one `GeckoRuntime` per Android process;
2. a `GeckoSession` hosted by the launcher activity;
3. `ensureBuiltIn("resource://android/assets/dna-extension/", "bke-dna-logger@jl-bke.com")`;
4. a message delegate registered for native app id `bke.dna.logger`;
5. sender/session validation before accepting extension messages;
6. transfer of the JSON message into the Kotlin ingress and then the Android evidence/storage pipeline;
7. ordinary manual ChatGPT browsing inside GeckoView;
8. device proof that a visible conversation phrase exists in captured raw response evidence.

Do not claim Android capture certification until that physical-device gate passes.

## Security boundary

The Android extension must never send cookies, Authorization headers, session tokens, or request headers to the native app. It may capture browser application response bodies plus bounded metadata already present in the desktop protocol.

DOM text remains witness evidence only. It never replaces raw network response evidence.

## Storage

The Android working root lives under the app-private `FilesDir/dna/captures` directory. SQLite is live/index state. `.dna` remains the only durability mechanism that can eventually authorize cleanup. Device synchronization must exchange verified `.dna` evidence rather than treating mutable SQLite files as canonical.
