#!/usr/bin/env python3
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
base = (
    repo
    / "android"
    / "app"
    / "src"
    / "main"
    / "kotlin"
    / "com"
    / "bke"
    / "dna"
    / "logger"
)
host = (base / "GeckoViewHost.kt").read_text(encoding="utf-8")
main = (base / "MainActivity.kt").read_text(encoding="utf-8")
interceptor = (repo / "extension" / "main-interceptor.js").read_text(encoding="utf-8")

# Single-tab browser behavior: same-origin requests for a new window are folded
# into the current GeckoSession. We track the current top-level origin ourselves
# because Gecko may report no triggerUri or hasUserGesture=false for framework-
# initiated navigation after a real user action.
for token in (
    "request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW",
    "request.triggerUri",
    "request.uri",
    "currentWebUri",
    "parseWebUri(url)?.let { currentWebUri = it }",
    "sameWebOrigin(",
    'browserDiagnostic("navigation_new_window_redirect_current")',
    "session.loadUri(request.uri)",
    "GeckoResult.deny()",
):
    assert token in host, token

redirect_block = host[
    host.index("private fun shouldRedirectNewWindowToCurrentSession"):
    host.index("private fun parseWebUri")
]
assert "request.hasUserGesture" not in redirect_block

# Browser Back follows Gecko history. Gecko publishes back-state changes to the
# Activity so API 33+ can register a predictive-back callback only while there is
# actual web history. This preserves system/default back behavior at history root.
for token in (
    "private val onCanGoBackChanged: (Boolean) -> Unit = {}",
    "@Volatile private var canGoBack = false",
    "override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean)",
    "this@GeckoViewHost.canGoBack = canGoBack",
    "if (changed) onCanGoBackChanged(canGoBack)",
    "fun goBackIfPossible(): Boolean",
    "if (!started || !session.isOpen || !canGoBack) return false",
    'browserDiagnostic("navigation_back")',
    "session.goBack()",
):
    assert token in host, token

for token in (
    "import android.window.OnBackInvokedCallback",
    "import android.window.OnBackInvokedDispatcher",
    "private var backInvokedCallback: OnBackInvokedCallback? = null",
    "GeckoViewHost(this, geckoView) { canGoBack ->",
    "updatePredictiveBackRegistration(canGoBack)",
    "Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU",
    "OnBackInvokedCallback {",
    "onBackInvokedDispatcher.registerOnBackInvokedCallback(",
    "OnBackInvokedDispatcher.PRIORITY_DEFAULT",
    "onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)",
    "geckoHost.goBackIfPossible()",
    "if (!handled) moveTaskToBack(true)",
):
    assert token in main, token

# Pre-API-33 devices still use Activity.onBackPressed; Android 16+ is handled by
# OnBackInvokedDispatcher because targetSdk 36 no longer dispatches onBackPressed.
back_start = main.index("override fun onBackPressed()")
back_end = main.index("private fun updatePredictiveBackRegistration", back_start)
legacy_back = main[back_start:back_end]
for token in (
    "AndroidDerivationScheduler.noteBrowserActivity()",
    "geckoHost.goBackIfPossible()",
    "super.onBackPressed()",
):
    assert token in legacy_back, token
assert legacy_back.index("geckoHost.goBackIfPossible()") < legacy_back.index("super.onBackPressed()")

# onNewSession remains Gecko-owned. GeckoView explicitly forbids calling loadUri
# from this callback; all single-tab folding must happen in onLoadRequest instead.
new_session_block = host[
    host.index("override fun onNewSession"):
    host.index("override fun onLoadError")
]
assert "session.loadUri" not in new_session_block
assert "return super.onNewSession(session, uri)" in new_session_block

# New-chat capture cannot depend on ChatGPT continuing to expose its live response
# through window.fetch. Once SPA navigation yields a real /c/<id>, request a small
# bounded set of canonical conversation snapshots in the logged-in page context.
# Submitting another turn re-arms the bounded series; leaving/hiding a chat takes
# one final snapshot. All snapshots still flow through publishCapture and the
# normal classifier/normalizer; no DOM message scraping or synthetic content.
for token in (
    "HYDRATION_DELAYS_MS = [1_500, 5_000, 15_000]",
    "function currentConversationId()",
    'segments[index] !== "c"',
    "async function hydrateConversation(conversationId)",
    '`/backend-api/conversation/${encodeURIComponent(conversationId)}`',
    'credentials: "include"',
    'cache: "no-store"',
    "await publishCapture(response, request)",
    "function scheduleConversationHydrationSeries(conversationId, force = false)",
    "if (!previousConversationId && currentId)",
    "scheduleConversationHydrationSeries(currentId, true)",
    "hydrateConversation(previousConversationId)",
    'document.addEventListener("submit"',
    'document.addEventListener("visibilitychange"',
    'window.addEventListener("pagehide", hydrateCurrentConversationOnce)',
):
    assert token in interceptor, token
for forbidden in (
    "querySelector(",
    "querySelectorAll(",
    "innerText",
    "textContent",
):
    assert forbidden not in interceptor, forbidden

# Camera capture uses the same proven staging shape as generic documents: the
# provider content URI is copied into the Gecko upload cache and returned as a
# file URI before the prompt is resolved.
for token in (
    "selected.mapNotNull { uri ->",
    "if (uri == cameraUri)",
    "AndroidDocumentUploadStager.stage(appContext, uri)",
    'browserDiagnostic("file_prompt_camera_staged")',
    'browserDiagnostic("file_prompt_camera_stage_failed")',
    "AndroidUserSelectedFileProvider.deleteIfOwned(appContext, uri)",
):
    assert token in host, token

for forbidden in (
    '"Show more"',
    '"/project"',
    '"g-p-"',
    "g-p-6a9eb5ec349c81919a219670a9d6f017",
):
    assert forbidden not in host, forbidden

print("android generic navigation + predictive browser back + canonical new-chat hydration + camera file-URI contract smoke PASS")
