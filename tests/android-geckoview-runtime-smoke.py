#!/usr/bin/env python3
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
kotlin = repo / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger"
manifest = (repo / "android" / "app" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")
main = (kotlin / "MainActivity.kt").read_text(encoding="utf-8")
provider = (kotlin / "GeckoRuntimeProvider.kt").read_text(encoding="utf-8")
host = (kotlin / "GeckoViewHost.kt").read_text(encoding="utf-8")
interceptor = (repo / "extension" / "main-interceptor.js").read_text(encoding="utf-8")
bridge = (
    repo / "android" / "app" / "src" / "main" / "assets" / "dna-extension" / "bridge.js"
).read_text(encoding="utf-8")

for token in (
    "GeckoView(this)",
    "GeckoViewHost(this, geckoView)",
    "geckoHost.start()",
    "geckoHost.stop()",
):
    if token not in main:
        raise SystemExit(f"MainActivity is missing GeckoView runtime contract {token!r}")

for token in (
    "private var instance: GeckoRuntime? = null",
    "GeckoRuntime.create(context.applicationContext)",
    "synchronized(this)",
):
    if token not in provider:
        raise SystemExit(f"process-lifetime GeckoRuntime guard missing {token!r}")

if "GeckoRuntime.create" in host:
    raise SystemExit("GeckoViewHost must obtain the process runtime from GeckoRuntimeProvider")

for token in (
    "GeckoSession()",
    "session.open(runtime)",
    "view.setSession(session)",
    'EXTENSION_URI = "resource://android/assets/dna-extension/"',
    'EXTENSION_ID = "bke-dna-logger@jl-bke.com"',
    'NATIVE_APP = "bke.dna.logger"',
    "ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)",
    "setMessageDelegate(",
    'CHATGPT_URL = "https://chatgpt.com/"',
    "session.loadUri(CHATGPT_URL)",
    "view.releaseSession()",
    "session.close()",
):
    if token not in host:
        raise SystemExit(f"GeckoView host contract missing {token!r}")

interceptor_diagnostics = (
    "interceptor_ready",
    "fetch_seen",
    "capture_candidate",
    "body_read_started",
    "body_read_complete",
    "body_read_failed",
    "capture_posted",
    "interceptor_load_error",
)
for event in interceptor_diagnostics:
    quoted = f'"{event}"'
    for name, source in (
        ("main interceptor", interceptor),
        ("Android extension bridge", bridge),
        ("GeckoView host", host),
    ):
        if quoted not in source:
            raise SystemExit(f"{name} is missing runtime diagnostic {event!r}")

bridge_diagnostics = (
    "capture_received",
    "capture_metadata_rejected",
    "capture_body_accepted",
    "capture_body_rejected",
    "capture_start_sent",
    "capture_forward_failed",
)
for event in bridge_diagnostics:
    quoted = f'"{event}"'
    for name, source in (
        ("Android extension bridge", bridge),
        ("GeckoView host", host),
    ):
        if quoted not in source:
            raise SystemExit(f"{name} is missing bridge runtime diagnostic {event!r}")

for token in (
    'emitDiagnostic("body_read_started")',
    "body = await clone.arrayBuffer()",
    'emitDiagnostic("body_read_failed")',
    'emitDiagnostic("body_read_complete")',
    'emitDiagnostic("capture_posted")',
):
    if token not in interceptor:
        raise SystemExit(f"main interceptor is missing body-read diagnostic contract {token!r}")

if interceptor.index('emitDiagnostic("body_read_started")') > interceptor.index("body = await clone.arrayBuffer()"):
    raise SystemExit("body_read_started must be emitted before the response clone is fully buffered")
if interceptor.index("body = await clone.arrayBuffer()") > interceptor.index('emitDiagnostic("body_read_complete")'):
    raise SystemExit("body_read_complete must be emitted only after the response clone is fully buffered")
if interceptor.index('kind: "capture"') > interceptor.index('emitDiagnostic("capture_posted")'):
    raise SystemExit("capture_posted must be emitted only after the capture packet is posted")

for token in (
    'await forwardDiagnostic("capture_received")',
    'await forwardDiagnostic("capture_metadata_rejected")',
    'packet.body instanceof ArrayBuffer',
    'await forwardDiagnostic("capture_body_rejected")',
    'await forwardDiagnostic("capture_body_accepted")',
    'type: "capture_start"',
    'await forwardDiagnostic("capture_start_sent")',
    'await forwardDiagnostic("capture_forward_failed")',
):
    if token not in bridge:
        raise SystemExit(f"Android extension bridge is missing capture-boundary diagnostic contract {token!r}")

if bridge.index('await forwardDiagnostic("capture_received")') > bridge.index("const metadata = packet.metadata"):
    raise SystemExit("capture_received must be emitted before capture packet validation")
if bridge.index('packet.body instanceof ArrayBuffer') > bridge.index('await forwardDiagnostic("capture_body_accepted")'):
    raise SystemExit("capture_body_accepted must be emitted only after the ArrayBuffer realm/type gate")
if bridge.index('type: "capture_start"') > bridge.index('await forwardDiagnostic("capture_start_sent")'):
    raise SystemExit("capture_start_sent must be emitted only after native capture_start is sent")

if 'private val DIAGNOSTIC_KEYS = setOf("type", "event")' not in host:
    raise SystemExit("runtime diagnostics must remain restricted to type + event only")
if 'type: "diagnostic"' not in bridge:
    raise SystemExit("runtime diagnostics must remain separate from capture evidence messages")

if host.index("ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)") > host.index("session.loadUri(CHATGPT_URL)"):
    raise SystemExit("ChatGPT navigation must not begin before built-in DNA extension registration")

if 'android:windowSoftInputMode="stateUnspecified|adjustResize"' not in manifest:
    raise SystemExit("GeckoView activity must resize for the Android soft keyboard")

print("android GeckoView runtime smoke PASS")
