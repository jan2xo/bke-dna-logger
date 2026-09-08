#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
interceptor = (ROOT / "extension/main-interceptor.js").read_text()
bridge = (ROOT / "android/app/src/main/assets/dna-extension/bridge.js").read_text()
host = (ROOT / "android/app/src/main/kotlin/com/bke/dna/logger/GeckoViewHost.kt").read_text()
activity = (ROOT / "android/app/src/main/kotlin/com/bke/dna/logger/MainActivity.kt").read_text()

for event in ("interceptor_ready", "fetch_seen", "capture_candidate"):
    assert f'publishDiagnostic("{event}")' in interceptor, event

assert 'kind: "runtime_diagnostic"' in interceptor
assert 'queueDiagnostic("interceptor_load_error")' in bridge
assert 'type: "runtime_diagnostic"' in bridge
assert 'DIAGNOSTIC_EVENTS' in bridge
assert 'RUNTIME_DIAGNOSTIC_EVENTS' in host
assert 'RUNTIME_DIAGNOSTIC_FIELDS = setOf("type", "event")' in host
assert 'Log.d(TAG, "Runtime diagnostic: $event")' in host
assert 'message.optString("type") == "runtime_diagnostic"' in host

# Runtime diagnostics must bypass durable evidence ingress and accept only type + event.
diag_branch = host.index('message.optString("type") == "runtime_diagnostic"')
ingress_branch = host.index("ingress.accept")
assert diag_branch < ingress_branch

# Physical UI proof showed the top button at Y=0. Require explicit status/cutout inset handling.
assert "setOnApplyWindowInsetsListener" in activity
assert "systemWindowInsetTop" in activity
assert "displayCutout?.safeInsetTop" in activity
assert "root.requestApplyInsets()" in activity

print("Android runtime observability and window-inset smoke PASS")
