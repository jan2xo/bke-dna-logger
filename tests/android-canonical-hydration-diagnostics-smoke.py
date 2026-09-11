#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
interceptor = (ROOT / "extension/main-interceptor.js").read_text(encoding="utf-8")

# Canonical hydration diagnostics expose whether the request was attempted,
# which HTTP status family came back, whether a 2xx response reached capture,
# and whether fetch/capture threw. They must not change retry, classifier,
# normalizer, or admission behavior.
for token in (
    '"hydration_requested"',
    '"hydration_status_2xx"',
    '"hydration_status_3xx"',
    '"hydration_status_4xx"',
    '"hydration_status_5xx"',
    '"hydration_status_other"',
    '"hydration_publish_started"',
    '"hydration_failed"',
    'emitDiagnostic("hydration_requested")',
    'emitHydrationStatus(response.status)',
    'emitDiagnostic("hydration_publish_started")',
    'emitDiagnostic("hydration_failed")',
    'if (!response.ok) return;',
    'await publishCapture(response, request);',
):
    assert token in interceptor, token

# The diagnostic patch must preserve the canonical endpoint and logged-in fetch.
for token in (
    '`/backend-api/conversation/${encodeURIComponent(conversationId)}`',
    'credentials: "include"',
    'cache: "no-store"',
    'HYDRATION_DELAYS_MS = [1_500, 5_000, 15_000]',
):
    assert token in interceptor, token

# Modern navigation may commit after history instrumentation has already checked
# window.location. Keep the existing committed hydration trigger, and instrument
# both Navigation API post-navigation boundaries with the already-whitelisted
# repeatable page_navigation_api diagnostic. This changes observability only.
for token in (
    'function scheduleCurrentConversationHydration(force = false)',
    'window.navigation.addEventListener("navigate", () => emitDiagnostic("page_navigation_api"));',
    'window.navigation.addEventListener("navigatesuccess", () => {',
    'window.navigation.addEventListener("currententrychange", () => {',
    'emitDiagnostic("page_navigation_api");',
    'scheduleCurrentConversationHydration(true);',
):
    assert token in interceptor, token

navigate_index = interceptor.index('window.navigation.addEventListener("navigate"')
success_index = interceptor.index('window.navigation.addEventListener("navigatesuccess"')
entry_index = interceptor.index('window.navigation.addEventListener("currententrychange"')
assert navigate_index < success_index < entry_index
assert interceptor.index('emitDiagnostic("page_navigation_api");', success_index) < interceptor.index('scheduleCurrentConversationHydration(true);', success_index)
assert interceptor.index('emitDiagnostic("page_navigation_api");', entry_index) > entry_index

# Existing history/popstate/submit coverage remains in place.
for token in (
    'history.pushState = function bkeDnaPushState',
    'history.replaceState = function bkeDnaReplaceState',
    'window.addEventListener("popstate"',
    'document.addEventListener("submit"',
):
    assert token in interceptor, token

# Do not silently evolve this diagnostic into non-2xx RAW capture or a retry loop.
assert interceptor.index('emitHydrationStatus(response.status)') < interceptor.index('if (!response.ok) return;')
assert interceptor.index('if (!response.ok) return;') < interceptor.index('emitDiagnostic("hydration_publish_started")')

print("android canonical hydration diagnostics + Navigation API boundary probe smoke PASS")
