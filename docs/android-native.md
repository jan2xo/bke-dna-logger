# BKE DNA Logger — Android-native architecture

Android is a first-class capture runtime, not a secondary export target.

## Locked direction

```text
BKE DNA Android app
  -> GeckoView
  -> bundled privileged WebExtension
  -> shared MAIN-world response interceptor
  -> GeckoView native messaging
  -> Android native ingress
  -> BKE DNA core/storage pipeline
  -> SQLite
  -> verified .dna
```

The Android and desktop runtimes must produce the same wire messages, normalized graph semantics, coverage semantics, SQLite meaning, and `.dna` formats. Platform code is limited to browser lifecycle, message transport, and platform storage paths.

## Current foundation gate

This stack establishes:

- `BKE.Dna.Logger.Core` as the shared wire-protocol owner.
- The desktop native host consuming the shared protocol.
- A `net10.0-android` application shell with private app-local DNA storage.
- A GeckoView-compatible built-in WebExtension manifest.
- The desktop `extension/main-interceptor.js` linked into the Android APK as an asset, so the browser-response capture logic is not forked.
- An Android WebExtension bridge that converts captured `ArrayBuffer` bodies into the same `capture_start` / ordered `capture_chunk` / `capture_end` protocol used by desktop.
- Android DOM witness messages using the same `dom_witness` wire type.
- An `AndroidWireIngress` seam that validates incoming messages through `BKE.Dna.Logger.Core` before the later GeckoView delegate hands them to the storage pipeline.

## GeckoView gate immediately after this one

The next stacked gate must bind a verified published GeckoView artifact and implement:

1. one `GeckoRuntime` per Android process;
2. a `GeckoSession` hosted by the launcher activity;
3. `ensureBuiltIn("resource://android/assets/dna-extension/", "bke-dna-logger@jl-bke.com")`;
4. a message delegate registered for native app id `bke.dna.logger`;
5. sender/session validation before accepting extension messages;
6. transfer of the JSON message into `AndroidWireIngress` and then the shared capture/storage pipeline;
7. ordinary manual ChatGPT browsing inside GeckoView;
8. device proof that a visible conversation phrase exists in captured raw response evidence.

Do not claim Android capture certification until that physical-device gate passes.

## Security boundary

The Android extension must never send cookies, Authorization headers, session tokens, or request headers to the native app. It may capture browser application response bodies plus bounded metadata already present in the desktop protocol.

DOM text remains witness evidence only. It never replaces raw network response evidence.

## Storage

The Android working root lives under the app-private `FilesDir/dna/captures` directory. SQLite is live/index state. `.dna` remains the only durability mechanism that can eventually authorize cleanup. Device synchronization must exchange verified `.dna` evidence rather than treating mutable SQLite files as canonical.
