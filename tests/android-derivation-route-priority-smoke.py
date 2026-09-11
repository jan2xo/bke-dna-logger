#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "android/app/src/main/kotlin/com/bke/dna/logger"

capture = (BASE / "AndroidCaptureStore.kt").read_text(encoding="utf-8")
host = (BASE / "GeckoViewHost.kt").read_text(encoding="utf-8")
priority = (BASE / "AndroidDerivationQueuePriority.kt").read_text(encoding="utf-8")
queue = (BASE / "AndroidDerivationQueue.kt").read_text(encoding="utf-8")

# Capture-route evidence only changes scheduling order. It does not bypass RAW,
# classification, or normalization. Promotion occurs after durable enqueue while
# capture priority is still active, so the worker cannot race ahead of promotion.
for token in (
    'AndroidDerivationScheduler.enqueue(',
    'AndroidDerivationQueuePriority.promote(',
    'priority = priorityForCaptureRoute(route)',
    'PRIORITY_CONVERSATION = 100',
    'PRIORITY_CONVERSATIONS_LIST = 50',
    'PRIORITY_BACKEND_API = 10',
    'PRIORITY_DEFAULT = 0',
    'ROUTE_CONVERSATION -> PRIORITY_CONVERSATION',
    'ROUTE_CONVERSATIONS_LIST -> PRIORITY_CONVERSATIONS_LIST',
    'ROUTE_BACKEND_API -> PRIORITY_BACKEND_API',
):
    assert token in capture, token

assert capture.index('AndroidDerivationScheduler.enqueue(') < capture.index('AndroidDerivationQueuePriority.promote(')
assert capture.index('AndroidDerivationQueuePriority.promote(') < capture.index('"capture_end"')

# A canonical conversation route is exactly one valid conversation-id segment.
# Nested helpers under /backend-api/conversation/ (for example autocomplete,
# stream_status, and textdocs) remain ordinary backend API traffic and must never
# receive conversation priority or misleading Gecko diagnostics.
route_tokens = (
    'CONVERSATION_PATH_PREFIX = "/backend-api/conversation/"',
    'CONVERSATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{7,127}")',
    'isCanonicalConversationPath(path) -> ROUTE_CONVERSATION',
    "return '/' !in conversationId && CONVERSATION_ID.matches(conversationId)",
    'path.startsWith("/backend-api/") -> ROUTE_BACKEND_API',
)
for token in route_tokens:
    assert token in capture, token
    assert token in host, token

for source in (capture, host):
    assert 'path.startsWith("/backend-api/conversation/") -> ROUTE_CONVERSATION' not in source
    assert 'path == "/backend-api/conversation" ||' not in source
    assert 'experimental/generate_autocompletions' not in source

# Hydration diagnostics emitted by the injected interceptor must survive the
# native Gecko diagnostic whitelist so physical runs can distinguish no-attempt,
# HTTP rejection, successful publish, and runtime failure without changing behavior.
for event in (
    'hydration_requested',
    'hydration_status_2xx',
    'hydration_status_3xx',
    'hydration_status_4xx',
    'hydration_status_5xx',
    'hydration_status_other',
    'hydration_publish_started',
    'hydration_failed',
):
    assert f'"{event}"' in host, event

# Promotion is monotonic: a later generic observation of the same source may not
# downgrade already-known conversation evidence.
for token in (
    'require(priority >= 0)',
    'if (priority == 0) return',
    '"source_sha256 = ? AND priority < ?"',
    'put("priority", priority)',
):
    assert token in priority, token

# Existing durable queue ordering remains authoritative.
for token in (
    'priority INTEGER NOT NULL DEFAULT 0',
    '"priority DESC, created_at ASC"',
):
    assert token in queue, token

# Do not turn priority into evidence admission or dropping.
for forbidden in (
    'classification_candidate',
    'normalization_skip',
    'delete(',
):
    assert forbidden not in priority, forbidden

print("android derivation route-priority smoke PASS")
