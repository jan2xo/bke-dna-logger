(() => {
  "use strict";

  const SOURCE = "bke-dna-logger";
  const EXTENSION_SOURCE = "bke-dna-logger-extension";
  const CHUNK_BYTES = 192 * 1024;
  let forwarding = Promise.resolve();

  function bytesToBase64(bytes) {
    let binary = "";
    const slice = 0x8000;

    for (let offset = 0; offset < bytes.length; offset += slice) {
      binary += String.fromCharCode(...bytes.subarray(offset, Math.min(offset + slice, bytes.length)));
    }

    return btoa(binary);
  }

  async function sendRuntime(message) {
    const response = await chrome.runtime.sendMessage(message);
    if (response && response.ok === false) {
      throw new Error(response.error || "native forwarding failed");
    }
  }

  function acknowledge(captureId, phase, sequence = null) {
    window.postMessage({
      source: EXTENSION_SOURCE,
      kind: "capture_ack",
      captureId,
      phase,
      sequence
    }, "*");
  }

  function toCaptureBytes(body) {
    try {
      ArrayBuffer.prototype.slice.call(body, 0, 0);
      const foreignBytes = new Uint8Array(body);
      const bytes = new Uint8Array(foreignBytes.byteLength);
      bytes.set(foreignBytes);
      return bytes;
    } catch (_) {
      return null;
    }
  }

  async function forwardStreamStart(packet) {
    const metadata = packet.metadata;
    if (!metadata || typeof metadata.captureId !== "string") {
      return;
    }
    await sendRuntime({
      type: "capture_start",
      ...metadata
    });
    acknowledge(metadata.captureId, "start");
  }

  async function forwardStreamChunk(packet) {
    if (typeof packet.captureId !== "string" || !Number.isSafeInteger(packet.sequence)) {
      return;
    }
    const bytes = toCaptureBytes(packet.body);
    if (bytes === null) {
      throw new Error("capture chunk failed cross-realm byte validation");
    }
    await sendRuntime({
      type: "capture_chunk",
      captureId: packet.captureId,
      sequence: packet.sequence,
      base64: bytesToBase64(bytes)
    });
    acknowledge(packet.captureId, "chunk", packet.sequence);
  }

  async function forwardStreamEnd(packet) {
    if (
      typeof packet.captureId !== "string" ||
      !Number.isSafeInteger(packet.byteLength) ||
      packet.byteLength < 0
    ) {
      return;
    }
    await sendRuntime({
      type: "capture_end",
      captureId: packet.captureId,
      byteLength: packet.byteLength
    });
    acknowledge(packet.captureId, "end");
  }

  // Backward-compatible full-buffer path for older injected interceptors.
  async function forwardCapture(metadata, body) {
    const bytes = toCaptureBytes(body);
    if (bytes === null) {
      return;
    }

    await sendRuntime({
      type: "capture_start",
      ...metadata
    });

    let sequence = 0;
    for (let offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
      const chunk = bytes.subarray(offset, Math.min(offset + CHUNK_BYTES, bytes.length));
      await sendRuntime({
        type: "capture_chunk",
        captureId: metadata.captureId,
        sequence,
        base64: bytesToBase64(chunk)
      });
      sequence += 1;
    }

    await sendRuntime({
      type: "capture_end",
      captureId: metadata.captureId,
      byteLength: metadata.byteLength
    });
  }

  window.addEventListener("message", event => {
    if (event.source !== window) {
      return;
    }

    const data = event.data;
    if (!data || data.source !== SOURCE) {
      return;
    }

    if (data.kind === "capture_start") {
      forwarding = forwarding.then(() => forwardStreamStart(data));
    } else if (data.kind === "capture_chunk") {
      forwarding = forwarding.then(() => forwardStreamChunk(data));
    } else if (data.kind === "capture_end") {
      forwarding = forwarding.then(() => forwardStreamEnd(data));
    } else if (data.kind === "capture") {
      forwarding = forwarding.then(() => forwardCapture(data.metadata, data.body));
    } else {
      return;
    }

    forwarding = forwarding.catch(error => {
      console.error("[BKE DNA] extension bridge failed", error);
    });
  });
})();
