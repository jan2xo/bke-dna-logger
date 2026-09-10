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

# Single-tab browser behavior: a user-initiated same-origin request that asks for
# a new window is folded into the current GeckoSession. This is generic browser
# plumbing; it must not key off ChatGPT labels, project IDs, or project path shapes.
for token in (
    "request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW",
    "request.hasUserGesture",
    "request.triggerUri",
    "request.uri",
    "sameWebOrigin(",
    'browserDiagnostic("navigation_new_window_redirect_current")',
    "session.loadUri(request.uri)",
    "GeckoResult.deny()",
):
    assert token in host, token

for forbidden in (
    '"Show more"',
    '"/project"',
    '"g-p-"',
    "g-p-6a9eb5ec349c81919a219670a9d6f017",
):
    assert forbidden not in host, forbidden

print("android generic single-tab navigation contract smoke PASS")
