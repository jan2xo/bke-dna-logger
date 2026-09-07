# BKE DNA Logger

BKE DNA Logger is a local-first conversation archaeology system.

## Locked architecture

- Ordinary manual ChatGPT browsing is the trigger; Playwright is not the primary capture design.
- Browser application response payloads are primary evidence.
- DOM inspection is verification/reconciliation only.
- Raw captures are preserved before parsing and are never silently replaced by normalized data.
- Native conversation/message identifiers and graph relationships are preserved when exposed.
- Raw captures are content-addressed with SHA-256 for deduplication.
- SQLite is the live working store; `.dna` is the durable archive format.
- Notion may be a searchable mirror, but Notion sync never makes SQLite data eligible for deletion.
- Only successful durable `.dna` persistence may eventually make live records eligible for cleanup.
- Authentication secrets, cookies, and authorization headers are outside the capture scope.

## First gate: POC-0

Manual ChatGPT browsing -> MAIN-world response interception -> extension bridge -> Chrome Native Messaging -> .NET 10 host -> exact captured application body on disk + metadata + SHA-256.

The first proof deliberately avoids parser assumptions, SQLite, Notion, and `.dna` packaging. It proves the evidence source first.
