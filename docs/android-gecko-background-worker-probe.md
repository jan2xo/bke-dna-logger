# Android Gecko background Worker probe

## Intent

Prove that the existing BKE DNA Logger GeckoView foundation can host a background BKE Worker browser session without Playwright/CDP or Android Accessibility.

This is a bounded experiment. It does not make Android the canonical BKE Worker runtime.

## Target shape

```text
Android foreground service
  -> application-owned GeckoRuntime
  -> service-owned GeckoSession
  -> bundled BKE Worker Gecko WebExtension
  -> ChatGPT DOM probe
  -> Gecko native messaging
  -> notification state
```

The existing DNA Logger browser/capture path remains separate.

## Required proof

- a dedicated foreground service owns the Worker GeckoSession;
- leaving the Activity does not intentionally close that session;
- the built-in Worker WebExtension reports only bounded page state;
- READY/BUSY/NO_COMPOSER state can reach Android native code;
- idle Worker mode does not hold a permanent wake lock;
- human authentication remains manual;
- physical-device background/screen-off proof is required before merge.

## Out of scope

- GitHub webhook ingestion;
- VPS, WebSocket, MQTT, or FCM transport;
- prompt dispatch;
- ChatGPT response/message scraping;
- authentication automation;
- DNA evidence protocol changes;
- production deployment.
