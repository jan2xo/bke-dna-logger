#!/usr/bin/env python3
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
host = (
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
    / "GeckoViewHost.kt"
).read_text(encoding="utf-8")

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

# onNewSession remains Gecko-owned. GeckoView explicitly forbids calling loadUri
# from this callback; all single-tab folding must happen in onLoadRequest instead.
new_session_block = host[
    host.index("override fun onNewSession"):
    host.index("override fun onLoadError")
]
assert "session.loadUri" not in new_session_block
assert "return super.onNewSession(session, uri)" in new_session_block

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

print("android generic single-tab navigation + camera file-URI contract smoke PASS")
