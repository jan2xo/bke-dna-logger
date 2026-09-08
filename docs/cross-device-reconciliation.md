# Cross-device DNA reconciliation contract

BKE DNA Logger never merges live SQLite databases across devices.

Each Android, macOS, and Windows installation remains an independent collector with its own SQLite working index and raw evidence store. Cross-device convergence happens only through verified conversation-scoped `.dna` artifacts and the platform-neutral reconciliation contract below.

## Identity

One logical ChatGPT conversation is identified by `conversationNativeId`.

Within that conversation, evidence is deduplicated using native graph/message identity and content-addressed source identity:

- `conversationNativeId` identifies the logical conversation.
- `nodeNativeId` preserves mapping-node identity.
- `messageNativeId` preserves message identity when exposed.
- raw response sources are identified by lowercase SHA-256.
- observations remain distinct even when they reference the same source SHA-256.

A source observed on Android, macOS, and Windows is one logical source with multiple provenance observations, not three logical messages.

## Portable reconciliation set

A reconciler may receive one or more verified conversation `.dna` archives. Before unioning evidence it MUST:

1. independently verify every `.dna` using the conversation archive verifier;
2. reject mixed `conversationNativeId` values;
3. union source SHA-256 identities without duplicating identical sources;
4. preserve every unique observation/provenance record;
5. union graph nodes by `nodeNativeId` while preserving `messageNativeId`, parents, children, timestamps, revisions, and branches;
6. recompute structural coverage from the union instead of trusting a single device's coverage status;
7. produce a new deterministic conversation artifact when the owner explicitly exports `.dna`.

Reconciliation MUST NOT mutate or attach another device's SQLite file.

## Manual export policy

Capture, normalization, deduplication, organization, and reconciliation may run automatically in working storage.

`.dna` export is manual. `.md` export is manual and conversation-scoped.

No automatic `.dna` export occurs when the user changes conversations, closes the app, or reaches a storage threshold.

## Storage notification

`1 GiB` (`1,073,741,824` bytes) is a soft notification threshold for BKE DNA working storage. It is not a hard limit.

Crossing the threshold MUST NOT stop capture, delete evidence, or automatically archive data. The UI should direct the owner to **Exports & Backups**.

## Backup versus archive

A backup preserves working state, including SQLite metadata and raw evidence. It is not proof of archival durability.

A `.dna` archive is a verified, conversation-scoped portable artifact. Cleanup eligibility remains gated on successful `.dna` verification; backup success alone never makes working evidence clearable.

## Platform parity

Android uses Kotlin. macOS and Windows use .NET. Every implementation must use the same constants and semantics:

```text
reconciliation contract: bke-dna-reconciliation-v1
storage warning bytes:   1073741824
archive scope:            conversation
archive export:           manual
markdown export:          manual
sqlite cross-device merge:false
```
