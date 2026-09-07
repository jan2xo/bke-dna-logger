# BKE DNA archive format v2 — conversation scope

A conversation-scoped `.dna` freezes one deterministic aggregated conversation state together with every raw network payload that contributes to that state.

It remains a ZIP container with a `.dna` extension for long-term inspectability and repairability.

## Required structure

```text
manifest.json
conversation/state.json
sources/<source-sha256>/raw.body
sources/<source-sha256>/normalized.json
sources/<source-sha256>/observations/<capture-id>.json
SHA256SUMS
```

For each source, classification evidence is included when present:

```text
sources/<source-sha256>/classification.json
```

Relevant corroboration evidence may also be included:

```text
witnesses/<witness-id>.json
reconciliations/<witness-id>.json
```

## Source closure

The manifest declares the exact set of raw source SHA-256 identifiers that compose the logical conversation snapshot.

Verification requires:

- the aggregated conversation state to declare the same source set;
- exactly one raw body for every declared source;
- each raw body SHA-256 to equal its source identity;
- exactly one normalized snapshot for every source;
- at least one capture observation for every source;
- every manifest evidence reference to exist with matching byte length and SHA-256;
- `SHA256SUMS` to describe exactly every payload entry;
- safe relative ZIP paths with no duplicates or traversal.

`complete` remains structural coverage only. The archive does not claim that the upstream service exposed records that were never observed by the browser logger.

## Determinism

The archive identity is derived from sorted evidence paths, hashes, lengths, and source bindings. Entries use deterministic ordering, a fixed ZIP timestamp, and no compression. The operational archival timestamp is deliberately excluded from archive bytes.

Rebuilding an unchanged conversation evidence set must therefore produce the same archive ID and whole-file SHA-256.

## Durability transaction

Building a file is not sufficient to authorize cleanup.

The logger first builds the archive, then independently reopens and verifies it. Only after successful verification does it begin one SQLite transaction covering every source SHA in the conversation.

Every included source must receive the same verified archive ID, archive SHA-256, and archival timestamp before `clearable = 1` is committed. If any source update fails, the transaction rolls back and **none** of the conversation sources become cleanup-eligible.

Notion is not part of this transaction and can never authorize cleanup.

## CLI

```text
--archive-conversation <conversation-native-id-or-key>
--verify-conversation-dna <path-to-dna>
```

The existing v1 single-source `.dna` commands remain supported independently.
