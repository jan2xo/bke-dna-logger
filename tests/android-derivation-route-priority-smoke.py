#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "android/app/src/main/kotlin/com/bke/dna/logger"

capture = (BASE / "AndroidCaptureStore.kt").read_text(encoding="utf-8")
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
