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
# window.location. The committed Navigation API event therefore schedules the
# same bounded canonical hydration series after the destination is active.
for token in (
    'function scheduleCurrentConversationHydration(force = false)',
    'window.navigation.addEventListener("navigatesuccess", () => {',
    'scheduleCurrentConversationHydration(true);',
):
    assert token in interceptor, token

assert interceptor.index('window.navigation.addEventListener("navigate"') < interceptor.index('window.navigation.addEventListener("navigatesuccess"')
assert interceptor.index('window.navigation.addEventListener("navigatesuccess"') < interceptor.index('scheduleCurrentConversationHydration(true);', interceptor.index('window.navigation.addEventListener("navigatesuccess"'))

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

print("android canonical hydration diagnostics + committed-navigation trigger smoke PASS")
