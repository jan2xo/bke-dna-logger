# BKE DNA Logger

BKE DNA Logger is a local-first conversation archaeology system.

## Locked architecture

- Ordinary manual ChatGPT browsing is the trigger; Playwright is not the primary capture design.
- Browser application response payloads are primary evidence.
- DOM inspection is verification/reconciliation only.
- Raw captures are preserved before parsing and are never silently replaced by normalized data.
- Native conversation/message identifiers and graph relationships are preserved when exposed.
- Raw captures are content-addressed with SHA-256 for deduplication.
- SQLite is local **Working Data**: Latest is the only live/writable generation; timestamped older generations are read-only recovery snapshots.
- Saved Working Data generations keep their SQLite projection plus logical conversation-state files and reuse the shared immutable SHA-addressed evidence pool instead of duplicating raw bodies.
- `.dna` is the self-contained durable archive format and remains the only durability gate for destructive local cleanup eligibility.
- CLEAN conversation export contains only actual `JAN` user turns and `RIGHT-HAND` assistant turns; tools, tool results, system/developer messages, diagnostics, IDs and archaeology metadata are excluded.
- RAW conversation export preserves the unfiltered captured conversation payload sources and capture observations represented by that logical conversation.
- Notion may be a searchable mirror, but Notion sync never makes SQLite data eligible for deletion.
- Authentication secrets, cookies, and authorization headers are outside the capture scope.

## Android Working Data

Android stores capture evidence and Working Data only in app-private storage.

- `Latest — Active` is always the default and the only generation that receives live capture writes.
- `Save & Start New Working Data` checkpoints and verifies the active SQLite, saves it under a timestamped generation, snapshots the indexed logical conversation-state files, starts a fresh Latest SQLite, then live capture resumes with a fresh native ingress.
- Selecting an older generation is read-only recovery. It can be browsed and exported as CLEAN/RAW without making that historical SQLite writable.
- Historical selection does not silently mutate Latest `.dna` durability state. A future explicit restore-as-new workflow may promote historical state into a new Latest generation without modifying the snapshot.
- The 1 GiB threshold is notification-only; there is no automatic purge or hard storage limit.

## Platform ownership

- Windows and macOS are implemented in .NET.
- Android is implemented natively in Kotlin.
- Cross-platform compatibility is defined by the DNA wire/evidence/archive contracts, identifiers, hashing rules, and archaeology semantics — not by forcing every platform to share one implementation language.
- GeckoView is consumed directly from Kotlin on Android. BKE does not generate a managed C# binding for the full Mozilla API surface.

## First gate: POC-0

Manual ChatGPT browsing -> MAIN-world response interception -> extension bridge -> Chrome Native Messaging -> .NET 10 host -> exact captured application body on disk + metadata + SHA-256.

The first proof deliberately avoids parser assumptions, SQLite, Notion, and `.dna` packaging. It proves the evidence source first.
