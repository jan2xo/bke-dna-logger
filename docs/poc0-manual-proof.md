# POC-0 manual proof

## Prerequisites

- Google Chrome
- .NET 10 SDK/runtime
- this repository checked out at the full POC-0 stack

## macOS proof path

1. Open `chrome://extensions` and enable Developer mode.
2. Load `extension/` as an unpacked extension.
3. Copy the extension ID Chrome assigns.
4. Run `scripts/install-native-host-macos.sh <extension-id>`.
5. Fully reload the BKE DNA extension after native-host installation.
6. Open ChatGPT normally and manually open an existing conversation.
7. Inspect the configured capture root.

Expected evidence layout:

```text
captures/
  bodies/
    <sha256>.body
  observations/
    <capture-id>.json
  partial/
```

The same response body observed repeatedly should produce one content-addressed body and multiple observations.

## What constitutes the first real PASS

- ChatGPT continues functioning normally.
- At least one application response body is persisted by the native host.
- The SHA-256 filename matches the persisted bytes.
- A phrase visibly present in the opened conversation can be found in a relevant captured body.
- No request authorization headers, cookies, or session tokens are required or written by the capture pipeline.

This proof does not yet claim that every stored response is a conversation payload or that conversation coverage is complete. Classification and graph normalization are later gates.
