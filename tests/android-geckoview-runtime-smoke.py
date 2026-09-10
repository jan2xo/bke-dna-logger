#!/usr/bin/env python3
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
kotlin = repo / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger"
manifest = (repo / "android" / "app" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")
main = (kotlin / "MainActivity.kt").read_text(encoding="utf-8")
provider = (kotlin / "GeckoRuntimeProvider.kt").read_text(encoding="utf-8")
host = (kotlin / "GeckoViewHost.kt").read_text(encoding="utf-8")
user_files = (kotlin / "AndroidUserSelectedFileProvider.kt").read_text(encoding="utf-8")
stager = (kotlin / "AndroidDocumentUploadStager.kt").read_text(encoding="utf-8")
runtime = (kotlin / "AndroidCaptureRuntime.kt").read_text(encoding="utf-8")
interceptor = (repo / "extension" / "main-interceptor.js").read_text(encoding="utf-8")
bridge = (repo / "android" / "app" / "src" / "main" / "assets" / "dna-extension" / "bridge.js").read_text(encoding="utf-8")

for token in (
    "GeckoView(this)",
    "GeckoViewHost(this, geckoView)",
    "geckoHost.start()",
    "startActivity(Intent(this@MainActivity, AndroidExportsBackupsActivity::class.java))",
    "geckoHost.onActivityResult(requestCode, resultCode, data)",
    "geckoHost.onRequestPermissionsResult(requestCode, permissions, grantResults)",
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

# Generic Files must not let provider-specific MIME aliases hide developer files
# such as Markdown. Media-only prompts retain media filtering so the already-good
# Photos path remains unchanged. Non-media selections are staged once into the
# app's short-lived browser cache with their original display name/MIME before
# they are confirmed back to Gecko.
for token in (
    "session.setPromptDelegate(promptDelegate)",
    "override fun onFilePrompt(",
    "GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE",
    "GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER",
    "Intent.ACTION_GET_CONTENT",
    "Intent.ACTION_OPEN_DOCUMENT_TREE",
    "Intent.EXTRA_ALLOW_MULTIPLE",
    "browserPickerMimeType(mimeTypes)",
    'mimeTypes.all { it.startsWith("image/") } -> "image/*"',
    'else -> "*/*"',
    "prepareGeckoFileUris(finalUris, cameraUri)",
    "AndroidDocumentUploadStager.shouldStage(appContext, uri)",
    "AndroidDocumentUploadStager.stage(appContext, uri)",
    "file_prompt_document_staged",
    "pending.prompt.confirm(appContext, geckoUris.toTypedArray())",
    "pending.prompt.confirm(appContext, geckoUris.first())",
    "pending.prompt.dismiss()",
    "collectSelectedUris(data)",
):
    assert token in host, token
assert "Intent.EXTRA_MIME_TYPES" not in host
assert "Intent.EXTRA_LOCAL_ONLY" not in host
file_prompt_block = host[host.index("private val promptDelegate"):host.index("private fun handleDiagnostic")]
assert "AndroidCaptureRuntime" not in file_prompt_block

for token in (
    "object AndroidDocumentUploadStager",
    "fun shouldStage(context: Context, uri: Uri): Boolean",
    "fun stage(context: Context, source: Uri): Uri?",
    "resolver.openInputStream(source)",
    "input.copyTo(output)",
    'extension == "md" || extension == "markdown"',
    'return "text/markdown"',
    "OpenableColumns.DISPLAY_NAME",
    "AndroidUserSelectedFileProvider.createStagedUploadFile(",
    "AndroidUserSelectedFileProvider.stagedUploadUri(",
):
    assert token in stager, token
for forbidden in (
    "AndroidCaptureRuntime",
    "AndroidRawEvidenceStore",
    "AndroidCaptureStore",
):
    assert forbidden not in stager, forbidden

for token in (
    "class AndroidUserSelectedFileProvider : ContentProvider()",
    'File(context.cacheDir, "user-selected")',
    '"${context.packageName}.user-selected-files"',
    "ParcelFileDescriptor.open(file, flags)",
    "OpenableColumns.DISPLAY_NAME",
    "OpenableColumns.SIZE",
    'STAGED_PATH = "staged"',
    'STAGED_FILE_NAME = Regex("upload-',
    "createStagedUploadFile(context: Context, name: String)",
    "stagedUploadUri(",
    'extension == "md" || extension == "markdown"',
    'return "text/markdown"',
    "MAX_CACHE_AGE_MS = 24L * 60L * 60L * 1000L",
    "cleanupStaleFiles(appContext)",
    "deleteIfOwned(context: Context, uri: Uri?)",
):
    assert token in user_files, token
assert "AndroidCaptureRuntime" not in user_files
assert "AndroidRawEvidenceStore" not in user_files
assert "AndroidCaptureStore" not in user_files

for token in (
    'android:name=".AndroidUserSelectedFileProvider"',
    'android:authorities="${applicationId}.user-selected-files"',
    'android:exported="false"',
    'android:grantUriPermissions="true"',
):
    assert token in manifest, token
for forbidden in (
    'android.permission.READ_MEDIA_IMAGES',
    'android.permission.READ_MEDIA_VIDEO',
    'android.permission.READ_EXTERNAL_STORAGE',
):
    assert forbidden not in manifest, forbidden

# Camera/media support remains parked: existing code may stay, but generic Files
# must not depend on camera permission or camera chooser intents.
normal_picker_tail = host[host.index("val contentIntent = Intent(Intent.ACTION_GET_CONTENT)"):host.index("private fun buildCameraLaunch")]
assert "Intent.EXTRA_INITIAL_INTENTS" not in normal_picker_tail

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

print("android GeckoView streamed runtime + generic Markdown/document upload smoke PASS")
