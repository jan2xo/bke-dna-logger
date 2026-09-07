# BKE DNA archive format v1

A `.dna` file is a deterministic ZIP container used as BKE DNA Logger's durable conversation-archaeology artifact.

The extension is `.dna`; the container format is ZIP so the archive can be inspected and repaired with ordinary tooling years later.

## Required entries

```text
manifest.json
raw/body.body
normalized/conversation.json
observations/<capture-id>.json
SHA256SUMS
```

When available for the same archived evidence set, the archive also includes:

```text
classification.json
witnesses/<witness-id>.json
reconciliations/<witness-id>.json
```

`raw/body.body` is the exact browser application-response body captured by the extension boundary. It is not described as raw TLS/TCP wire traffic.

## Determinism

Archive identity is derived from the sorted evidence paths, their SHA-256 hashes, and byte lengths. ZIP entries are written in deterministic order with a fixed ZIP timestamp and no compression. Rebuilding the same evidence set must produce the same archive ID and archive SHA-256.

The manifest intentionally has no build timestamp. Operational archival time is recorded in SQLite only after verification and therefore cannot perturb archive bytes.

## Verification

A `.dna` archive is not durable merely because a ZIP file exists. Verification must reopen the finished archive and prove all of the following:

- every entry path is relative and traversal-safe;
- there are no duplicate paths;
- `manifest.json` and `SHA256SUMS` exist;
- `SHA256SUMS` describes exactly every payload entry;
- every payload checksum matches the reopened bytes;
- every manifest evidence reference exists and has the declared checksum and byte length;
- exactly one `raw_body` evidence entry exists;
- the raw-body checksum equals the manifest source SHA-256.

The whole `.dna` file is then SHA-256 hashed.

## Cleanup guardrail

Archive construction alone does **not** authorize cleanup.

Only after successful independent verification may the logger update SQLite `durability_state` with:

- `dna_archive_id`
- `dna_archive_sha256`
- `archived_at`
- `clearable = 1`

The database constraint rejects `clearable = 1` without those durability fields. Notion synchronization is not part of this condition and can never authorize SQLite cleanup.
