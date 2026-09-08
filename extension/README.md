# BKE DNA Logger extension — POC-0

This unpacked Manifest V3 extension observes selected text/JSON/event-stream `fetch()` responses while the owner uses ChatGPT normally.

## What it does

1. Runs `main-interceptor.js` in the page MAIN world at `document_start`.
2. Calls the original `fetch()` unchanged.
3. Clones eligible responses and reads the clone as an `ArrayBuffer`.
4. Transfers the clone to the isolated bridge with `window.postMessage`.
5. Splits the capture into 192 KiB chunks and base64-encodes each chunk.
6. Sends `capture_start`, ordered `capture_chunk`, and `capture_end` messages to the extension service worker.
7. The service worker forwards them to Chrome Native Messaging host `com.bke.dna_logger`.

## Explicit non-goals for this stack

- no private ChatGPT endpoint names are hardcoded
- no request headers, cookies, authorization headers, or session tokens are collected
- no conversation parser
- no completeness claim
- no DOM scraping as source of truth
- no Playwright

The next stacked change implements the .NET 10 Native Messaging framing and content-addressed evidence store.
