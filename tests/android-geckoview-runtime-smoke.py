#!/usr/bin/env python3
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
kotlin = repo / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger"
manifest = (repo / "android" / "app" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")
main = (kotlin / "MainActivity.kt").read_text(encoding="utf-8")
provider = (kotlin / "GeckoRuntimeProvider.kt").read_text(encoding="utf-8")
host = (kotlin / "GeckoViewHost.kt").read_text(encoding="utf-8")
runtime = (kotlin / "AndroidCaptureRuntime.kt").read_text(encoding="utf-8")
interceptor = (repo / "extension" / "main-interceptor.js").read_text(encoding="utf-8")
bridge = (repo / "android" / "app" / "src" / "main" / "assets" / "dna-extension" / "bridge.js").read_text(encoding="utf-8")

for token in (
    "GeckoView(this)",
    "GeckoViewHost(this, geckoView)",
    "geckoHost.start()",
    "startActivity(Intent(this@MainActivity, AndroidExportsBackupsActivity::class.java))",
    "geckoHost.stop()",
):
    assert token in main, token

# Opening management must not destroy/recreate the browser session.
exports_block = main[main.index('text = "Working Data & Exports"'):main.index('root.addView', main.index('text = "Working Data & Exports"'))]
assert "geckoHost.stop()" not in exports_block
assert "capturePausedForWorkingData" not in main
assert "override fun onResume()" not in main

for token in (
    "private var instance: GeckoRuntime? = null",
    "GeckoRuntime.create(context.applicationContext)",
    "synchronized(this)",
):
    assert token in provider, token

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
    "AndroidCaptureRuntime.start(appContext)",
    "AndroidCaptureRuntime.accept(",
    "AndroidCaptureRuntime.stop()",
    "view.releaseSession()",
    "session.close()",
):
    assert token in host, token
assert "AndroidWireIngress(activity.applicationContext)" not in host

for token in (
    "pauseForStorageMutation",
    "resumeAfterStorageMutation",
    "withStorageMutationPause",
    "AndroidDerivationScheduler.start(appContext)",
    "AndroidCaptureStore.awaitBackgroundDerivationIdle(appContext)",
):
    assert token in runtime, token

route_tokens = (
    'ROUTE_CONVERSATION = "capture_route_conversation"',
    'ROUTE_CONVERSATIONS_LIST = "capture_route_conversations_list"',
    'ROUTE_BACKEND_API = "capture_route_backend_api"',
    'ROUTE_PUBLIC_API = "capture_route_public_api"',
    'ROUTE_OTHER = "capture_route_other"',
    "private fun classifyCaptureRoute(requestUrl: String?): String",
    'path == "/backend-api/conversations"',
    'path.startsWith("/backend-api/conversation/")',
    'path.startsWith("/backend-api/")',
    'path.startsWith("/public-api/")',
    'if (type == "capture_start")',
    'Log.d(TAG, "DNA capture route: ${classifyCaptureRoute(requestUrl)}")',
)
for token in route_tokens:
    assert token in host, token

for forbidden in (
    'Log.d(TAG, requestUrl',
    'Log.i(TAG, requestUrl',
    'Log.w(TAG, requestUrl',
    'Log.e(TAG, requestUrl',
    '"DNA capture route: $requestUrl"',
):
    assert forbidden not in host, forbidden

# The response clone is streamed from Gecko instead of fully materialized.
for token in (
    "STREAM_CHUNK_BYTES = 128 * 1024",
    "clone.body?.getReader()",
    "await reader.read()",
    "waitForAck",
    'kind: "capture_start"',
    'kind: "capture_chunk"',
    'kind: "capture_end"',
    'phase === "chunk"',
    'kind !== "capture_ack"',
    "byteLength += chunk.byteLength",
):
    assert token in interceptor, token
assert "clone.arrayBuffer()" not in interceptor
assert "body = await clone.arrayBuffer()" not in interceptor

interceptor_diagnostics = (
    "interceptor_ready", "fetch_seen", "capture_candidate", "body_read_started",
    "body_read_complete", "body_read_failed", "capture_posted", "interceptor_load_error",
)
for event in interceptor_diagnostics:
    quoted = f'"{event}"'
    for source in (interceptor, bridge, host):
        assert quoted in source, event

for token in (
    'EXTENSION_SOURCE = "bke-dna-logger-extension"',
    'kind: "capture_ack"',
    "forwardStreamStart",
    "forwardStreamChunk",
    "forwardStreamEnd",
    "ArrayBuffer.prototype.slice.call(body, 0, 0)",
    "const foreignBytes = new Uint8Array(body)",
    "const bytes = new Uint8Array(foreignBytes.byteLength)",
    "bytes.set(foreignBytes)",
    'type: "capture_start"',
    'type: "capture_chunk"',
    'type: "capture_end"',
    "byteLength: packet.byteLength",
):
    assert token in bridge, token
assert "packet.body instanceof ArrayBuffer" not in bridge

bridge_diagnostics = (
    "capture_received", "capture_metadata_rejected", "capture_body_accepted",
    "capture_body_rejected", "capture_start_sent", "chunk_encode_started",
    "chunk_encode_complete", "chunk_send_started", "chunk_send_complete",
    "capture_end_sent", "capture_forward_failed",
)
for event in bridge_diagnostics:
    quoted = f'"{event}"'
    assert quoted in bridge and quoted in host, event

assert 'private val DIAGNOSTIC_KEYS = setOf("type", "event")' in host
assert 'type: "diagnostic"' in bridge
assert host.index("ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)") < host.index("session.loadUri(CHATGPT_URL)")
assert 'android:windowSoftInputMode="stateUnspecified|adjustResize"' in manifest

print("android GeckoView streamed runtime/session-preservation smoke PASS")
