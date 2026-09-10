#!/usr/bin/env python3
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
interceptor = (repo / "extension" / "main-interceptor.js").read_text(encoding="utf-8")
bridge = (repo / "android" / "app" / "src" / "main" / "assets" / "dna-extension" / "bridge.js").read_text(encoding="utf-8")
host = (repo / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger" / "GeckoViewHost.kt").read_text(encoding="utf-8")

# Runtime-only navigation archaeology. These probes identify the generic browser
# primitive used by a SPA without reading DOM labels, URLs, project IDs, titles,
# route paths, or other page content.
events = (
    "page_window_open",
    "page_history_push_state",
    "page_history_replace_state",
    "page_navigation_api",
    "page_popstate",
    "page_hashchange",
)
for event in events:
    quoted = f'"{event}"'
    assert quoted in interceptor, event
    assert quoted in bridge, event
    assert quoted in host, event

for token in (
    "const originalWindowOpen = window.open;",
    "Reflect.apply(originalWindowOpen, this, args)",
    "const originalPushState = history.pushState;",
    "Reflect.apply(originalPushState, this, args)",
    "const originalReplaceState = history.replaceState;",
    "Reflect.apply(originalReplaceState, this, args)",
    'window.addEventListener("popstate"',
    'window.addEventListener("hashchange"',
):
    assert token in interceptor, token

# The probe must never encode the project UI or destination.
for forbidden in (
    '"Show more"',
    '"/project"',
    '"g-p-"',
):
    assert forbidden not in interceptor, forbidden

print("android generic SPA route runtime probe smoke PASS")
