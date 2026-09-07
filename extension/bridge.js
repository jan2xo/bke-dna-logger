(() => {
  "use strict";

  const SOURCE = "bke-dna-logger";
  const CHUNK_BYTES = 192 * 1024;

  function bytesToBase64(bytes) {
    let binary = "";
    const slice = 0x8000;

    for (let offset = 0; offset < bytes.length; offset += slice) {
      binary += String.fromCharCode(...bytes.subarray(offset, Math.min(offset + slice, bytes.length)));
    }

    return btoa(binary);
  }

  async function forwardCapture(metadata, body) {
    const bytes = new Uint8Array(body);

    await chrome.runtime.sendMessage({
      type: "capture_start",
      ...metadata
    });

    let sequence = 0;
    for (let offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
      const chunk = bytes.subarray(offset, Math.min(offset + CHUNK_BYTES, bytes.length));
      await chrome.runtime.sendMessage({
        type: "capture_chunk",
        captureId: metadata.captureId,
        sequence,
        base64: bytesToBase64(chunk)
      });
      sequence += 1;
    }

    await chrome.runtime.sendMessage({
      type: "capture_end",
      captureId: metadata.captureId
    });
  }

  window.addEventListener("message", event => {
    if (event.source !== window) {
      return;
    }

    const data = event.data;
    if (!data || data.source !== SOURCE || data.kind !== "capture") {
      return;
    }

    forwardCapture(data.metadata, data.body).catch(error => {
      console.error("[BKE DNA] extension bridge failed", error);
    });
  });
})();
