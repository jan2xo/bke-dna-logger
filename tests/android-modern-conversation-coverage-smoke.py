#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "android/app/src/main/kotlin/com/bke/dna/logger"

classifier = (BASE / "AndroidConversationPayloadClassifier.kt").read_text(encoding="utf-8")
resolver = (BASE / "AndroidConversationSourceIdentity.kt").read_text(encoding="utf-8")
messages = (BASE / "AndroidMessagesNormalizationEngine.kt").read_text(encoding="utf-8")
streaming_messages = (BASE / "AndroidStreamingMessagesNormalizationEngine.kt").read_text(encoding="utf-8")
event_stream = (BASE / "AndroidEventStreamNormalizationEngine.kt").read_text(encoding="utf-8")
dispatcher = (BASE / "AndroidConversationNormalizationDispatcher.kt").read_text(encoding="utf-8")
recovery = (BASE / "AndroidNormalizationRecovery.kt").read_text(encoding="utf-8")

# Admission policy stays fixed. This patch expands explicit normalization
# representations; it does not lower classifier thresholds.
for token in (
    'score >= 28',
    'score >= 55',
    '"conversation_payload_candidate"',
    '"messages"',
    '"authored_message_shape"',
    '"text/event-stream"',
):
    assert token in classifier, token

# Missing payload conversation_id may only be recovered from exact metadata
# persisted for the same source SHA. Conflicting metadata returns null.
for token in (
    '"captures"',
    'arrayOf("request_url", "page_url")',
    '"sha256 = ?"',
    'extractFromRequestUrl(requestUrl)',
    'extractFromPageUrl(pageUrl)',
    '"conversation_id"',
    '"conversationId"',
    'segments[index] == "c"',
    'if (candidates.size != 1) return null',
    '"capture_request_url"',
    '"capture_page_url"',
):
    assert token in resolver, token
for forbidden in ('randomUUID', 'UUID.randomUUID', 'sourceSha256.take(', 'hashCode()'):
    assert forbidden not in resolver, forbidden

# Root messages-array-v0 stays valid, while one unambiguous nested messages
# envelope and object-backed messages collections are explicitly supported.
for token in (
    'PARSER = "messages-array-v0"',
    'PARSER_ENVELOPE = "messages-envelope-v1"',
    'root.optJSONArray("messages") ?: root.optJSONObject("messages")',
    'normalization_skip_no_unambiguous_messages_envelope',
    'AndroidConversationSourceIdentity.resolve(appContext, sourceSha256)',
    '"payload_conversation_id"',
    '"conversationIdentityBasis"',
    'is JSONArray -> buildList',
    'is JSONObject -> buildList',
    'RECOGNIZED_ROLES',
):
    assert token in messages, token
for forbidden in ('message.opt("parent")', 'message.optJSONArray("children")', 'UUID.randomUUID'):
    assert forbidden not in messages, forbidden

# Normal-sized messages now preserve exactly the same evidence-backed title
# semantics as the oversized streaming path. Only captured string titles from
# the root/messages envelope are eligible; conflicting evidence fails closed.
for token in (
    'capturedTitle(root.opt("title"))',
    'capturedTitle(envelope.container.opt("title"))',
    'resolveDisplayTitle(',
    'messages_display_title_found',
    'messages_display_title_conflict',
    'writer.name("displayTitle").value(displayTitle)',
    'DISPLAY_TITLE_LIMIT = 240',
):
    assert token in messages, token
for forbidden in (
    'setOf("user")',
    'first user',
    'first JAN',
    'UUID.randomUUID',
):
    assert forbidden not in messages, forbidden

# Oversized messages normalization keeps title handling evidence-backed and
# bounded: only a captured string title on the root/messages envelope may become
# displayTitle. Conflicts omit the title instead of synthesizing a replacement.
for token in (
    '"title" -> title = readCapturedTitle(reader)',
    'resolveDisplayTitle(scan.rootTitle, envelope.title)',
    'messages_display_title_found',
    'messages_display_title_conflict',
    'writer.name("displayTitle").value(displayTitle)',
    'DISPLAY_TITLE_LIMIT = 120',
    'JsonToken.STRING -> reader.nextString().trim()',
):
    assert token in streaming_messages, token
for forbidden in (
    'setOf("user")',
    'first user',
    'first JAN',
    'UUID.randomUUID',
):
    assert forbidden not in streaming_messages, forbidden

# New-chat/event-stream responses are a separate graphless representation.
# Later observations of the same message id replace earlier incremental stream
# versions; no parent/child edges are manufactured.
for token in (
    'PARSER = "event-stream-messages-v1"',
    'line.startsWith("data:", ignoreCase = true)',
    'payload == "[DONE]"',
    'collectAuthoredMessages(event)',
    'messagesById[id] = message',
    'collectScalarValues(event, "conversation_id")',
    'AndroidConversationSourceIdentity.resolve(appContext, sourceSha256)',
    '"event_stream_conversation_id"',
    '"conversationIdentityBasis"',
    'event_stream_normalization_complete',
):
    assert token in event_stream, token
for forbidden in ('message.opt("parent")', 'message.optJSONArray("children")', 'UUID.randomUUID'):
    assert forbidden not in event_stream, forbidden

# Dispatcher chooses event-stream explicitly, keeps mapping-graph priority, and
# may route a classifier-proven nested messages signal to the strict messages
# normalizer rather than rejecting it before that normalizer can inspect it.
for token in (
    'AndroidEventStreamNormalizationEngine(appContext)',
    'contentType?.contains("text/event-stream", ignoreCase = true)',
    'looksLikeEventStream(rawText)',
    'normalization_representation_event_stream',
    'root.optJSONObject("mapping") != null',
    'root.optJSONArray("messages") != null',
    'root.optJSONObject("messages") != null',
    '"messages" in classifierSignals',
    'messagesNormalizer.normalizeCandidate(sourceSha256)',
):
    assert token in dispatcher, token
assert dispatcher.index('root.optJSONObject("mapping") != null') < dispatcher.index('"messages" in classifierSignals')

# Existing alpha.4 DONE candidates that lacked a normalized derivative get one
# alpha.5 reprocessing opportunity from already durable RAW.
for token in (
    'modern-messages-normalizer-v2',
    'RECOVERED_MODERN_MESSAGES',
    'n.source_sha256 IS NULL',
    'conversation_payload_candidate',
):
    assert token in recovery, token

print('android modern conversation representation + captured title coverage smoke PASS')
