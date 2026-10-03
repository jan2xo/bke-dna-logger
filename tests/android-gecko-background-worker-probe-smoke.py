#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
base = root / "android/app/src/main"
service = (base / "kotlin/com/bke/dna/logger/AndroidGeckoWorkerService.kt").read_text(encoding="utf-8")
activity = (base / "kotlin/com/bke/dna/logger/MainActivity.kt").read_text(encoding="utf-8")
android_manifest = (base / "AndroidManifest.xml").read_text(encoding="utf-8")
extension_manifest = (base / "assets/worker-extension/manifest.json").read_text(encoding="utf-8")
probe = (base / "assets/worker-extension/worker-probe.js").read_text(encoding="utf-8")

# Service, not Activity, owns the experimental Worker GeckoSession.
for token in (
    "class AndroidGeckoWorkerService : Service()",
    "GeckoRuntimeProvider.get(applicationContext)",
    "private val session = GeckoSession()",
    "session.open(runtime)",
    "ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)",
    "setMessageDelegate(",
    "session.loadUri(CHATGPT_URL)",
    "return START_STICKY",
):
    assert token in service, token

assert "PowerManager" not in service
assert "WakeLock" not in service
assert 'WORKER_ID = "android-worker-a"' in service
assert 'NATIVE_APP = "bke.worker.android"' in service

# Visible-app controls are the only experiment start/stop entry points.
assert "AndroidGeckoWorkerService.ensureRunning(this@MainActivity)" in activity
assert "AndroidGeckoWorkerService.stop(this@MainActivity)" in activity

# Android foreground-service boundary is explicit and non-exported.
for token in (
    'android:name=".AndroidGeckoWorkerService"',
    'android:exported="false"',
    'android:foregroundServiceType="specialUse"',
    'android:stopWithTask="false"',
    "Experimental service-owned GeckoSession for a user-started BKE Worker browser probe",
):
    assert token in android_manifest, token

# Gecko extension is bounded to ChatGPT and native messaging only.
for token in (
    '"manifest_version": 2',
    '"nativeMessaging"',
    '"nativeMessagingFromContent"',
    '"geckoViewAddons"',
    '"https://chatgpt.com/*"',
    '"https://chat.openai.com/*"',
    '"worker-probe.js"',
):
    assert token in extension_manifest, token

for token in (
    'const NATIVE_APP = "bke.worker.android"',
    '[data-testid="prompt-textarea"]',
    '[data-testid="stop-button"]',
    'browser.runtime.sendNativeMessage(NATIVE_APP, payload)',
    'composerAvailable',
    'turnBusy',
):
    assert token in probe, token

# Probe must not harvest conversation text or authentication material.
for forbidden in (
    "document.body.innerText",
    "textContent",
    "cookie",
    "Authorization",
    "localStorage",
    "sessionStorage",
):
    assert forbidden not in probe, forbidden

print("android Gecko background Worker probe smoke PASS")
