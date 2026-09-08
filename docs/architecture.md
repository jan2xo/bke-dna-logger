# POC-0 architecture

## Goal

Prove that ordinary manual ChatGPT browsing can produce independently persisted browser-response evidence without Playwright or DOM scraping as the primary source.

```text
ChatGPT page
    -> MAIN-world fetch interception
    -> isolated extension bridge
    -> extension service worker
    -> Chrome Native Messaging
    -> .NET 10 native host
    -> SHA-256 content-addressed capture on disk
```

## Evidence boundary

POC-0 preserves the application response body exposed to page JavaScript after browser/network decoding and before BKE conversation parsing. It does not claim to preserve TLS/TCP wire bytes.

## Guardrails

- Do not hardcode a private ChatGPT conversation endpoint.
- Do not collect cookies, authorization headers, or session tokens.
- Do not flatten conversation branches.
- Do not claim completeness merely because some messages were observed.
- Do not introduce SQLite, Notion sync, or `.dna` packaging until capture evidence is proven.
- Raw response evidence must remain independently recoverable from any future parser.
