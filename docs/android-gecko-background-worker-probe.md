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

## Implemented experiment boundary

- `AndroidGeckoWorkerService` owns a second, non-visual `GeckoSession` on the shared application `GeckoRuntime`.
- The service is `START_STICKY`, foreground, non-exported, and `stopWithTask=false`.
- Idle Worker mode intentionally owns **no wake lock**.
- A dedicated built-in Gecko WebExtension reports only:
  - page URL;
  - composer availability;
  - busy/idle state;
  - observation timestamp.
- Native Android reduces those observations to notification state:
  - `STARTING`
  - `READY`
  - `BUSY`
  - `NO_COMPOSER`
  - `FAILED`
- MainActivity exposes explicit Start/Stop Worker Probe controls. The service is not silently started from the background.

## Physical-device proof procedure

1. Open DNA Logger normally and keep the existing ChatGPT browser authenticated by hand.
2. Tap **Start Worker Probe** while the Activity is visible.
3. Confirm the persistent notification appears as `BKE Worker — android-worker-a • ...`.
4. Observe `READY` on an idle ChatGPT page and `BUSY` while a turn is generating.
5. Leave the app / lock the screen for a bounded interval.
6. Confirm the notification remains and the Worker service was not destroyed.
7. Return to the app and verify human authentication is unchanged.
8. Repeat with Wi-Fi/mobile-data transition.
9. Do not merge until this physical-device background gate is recorded.
