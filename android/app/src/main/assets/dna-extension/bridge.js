(() => {
  "use strict";

  const SOURCE = "bke-dna-logger";
  const EXTENSION_SOURCE = "bke-dna-logger-extension";
  const NATIVE_APP = "bke.dna.logger";
  const CHUNK_BYTES = 192 * 1024;
  const BINARY_STRING_SLICE = 32 * 1024;
  const DIAGNOSTIC_EVENTS = new Set([
    "interceptor_ready",
    "fetch_seen",
    "capture_candidate",
    "body_read_started",
    "body_read_complete",
    "body_read_failed",
    "capture_posted",
    "capture_received",
    "capture_metadata_rejected",
    "capture_body_accepted",
    "capture_body_rejected",
    "capture_start_sent",
    "chunk_encode_started",
    "chunk_encode_complete",
    "chunk_send_started",
    "chunk_send_complete",
    "capture_end_sent",
    "capture_forward_failed",
    "interceptor_load_error"
  ]);
  const forwardedDiagnostics = new Set();
  let forwarding = Promise.resolve();

  async function sendNative(message) {
    await browser.runtime.sendNativeMessage(NATIVE_APP, message);
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

  async function forwardDiagnostic(event) {
    if (!DIAGNOSTIC_EVENTS.has(event) || forwardedDiagnostics.has(event)) {
      return;
    }

    forwardedDiagnostics.add(event);
    await sendNative({
      type: "diagnostic",
      event
    });
  }

  function injectMainInterceptor() {
    const script = document.createElement("script");
    script.src = browser.runtime.getURL("main-interceptor.js");
    script.async = false;
    script.addEventListener("load", () => script.remove(), { once: true });
    script.addEventListener("error", () => {
      forwardDiagnostic("interceptor_load_error")
        .catch(error => console.debug("[BKE DNA Android] diagnostic forwarding failed", error));
      script.remove();
    }, { once: true });

    const parent = document.documentElement || document.head;
    if (parent) {
      parent.appendChild(script);
      return;
    }

    document.addEventListener("DOMContentLoaded", () => {
      (document.documentElement || document.head)?.appendChild(script);
    }, { once: true });
  }

  function bytesToBase64(bytes) {
    let binary = "";
    for (let offset = 0; offset < bytes.length; offset += BINARY_STRING_SLICE) {
      const slice = bytes.subarray(offset, Math.min(offset + BINARY_STRING_SLICE, bytes.length));
      binary += String.fromCharCode.apply(null, slice);
    }
    return btoa(binary);
  }

  function toCaptureBytes(body, expectedByteLength = null) {
    if (expectedByteLength !== null && (!Number.isSafeInteger(expectedByteLength) || expectedByteLength < 0)) {
      return null;
    }

    try {
      ArrayBuffer.prototype.slice.call(body, 0, 0);
      const foreignBytes = new Uint8Array(body);
      const bytes = new Uint8Array(foreignBytes.byteLength);
      bytes.set(foreignBytes);
      if (expectedByteLength !== null && bytes.byteLength !== expectedByteLength) {
        return null;
      }
      return bytes;
    } catch (_) {
      return null;
    }
  }

  async function forwardStreamStart(packet) {
    await forwardDiagnostic("capture_received");
    const metadata = packet.metadata;
    if (!metadata || typeof metadata.captureId !== "string") {
      await forwardDiagnostic("capture_metadata_rejected");
      return;
    }

    try {
      await sendNative({
        type: "capture_start",
        captureId: metadata.captureId,
        pageUrl: metadata.pageUrl,
        requestUrl: metadata.requestUrl,
        method: metadata.method,
        status: metadata.status,
        contentType: metadata.contentType,
        initiator: metadata.initiator,
        capturedAt: metadata.capturedAt,
        fidelity: metadata.fidelity
      });
      await forwardDiagnostic("capture_start_sent");
      acknowledge(metadata.captureId, "start");
    } catch (error) {
      try { await forwardDiagnostic("capture_forward_failed"); } catch (_) {}
      throw error;
    }
  }

  async function forwardStreamChunk(packet) {
    if (typeof packet.captureId !== "string" || !Number.isSafeInteger(packet.sequence) || packet.sequence < 0) {
      await forwardDiagnostic("capture_metadata_rejected");
      return;
    }

    const bytes = toCaptureBytes(packet.body);
    if (bytes === null) {
      await forwardDiagnostic("capture_body_rejected");
      return;
    }
    await forwardDiagnostic("capture_body_accepted");

    try {
      await forwardDiagnostic("chunk_encode_started");
      const base64 = bytesToBase64(bytes);
      await forwardDiagnostic("chunk_encode_complete");
      await forwardDiagnostic("chunk_send_started");
      await sendNative({
        type: "capture_chunk",
        captureId: packet.captureId,
        sequence: packet.sequence,
        base64
      });
      await forwardDiagnostic("chunk_send_complete");
      acknowledge(packet.captureId, "chunk", packet.sequence);
    } catch (error) {
      try { await forwardDiagnostic("capture_forward_failed"); } catch (_) {}
      throw error;
    }
  }

  async function forwardStreamEnd(packet) {
    if (
      typeof packet.captureId !== "string" ||
      !Number.isSafeInteger(packet.byteLength) ||
      packet.byteLength < 0
    ) {
      await forwardDiagnostic("capture_metadata_rejected");
      return;
    }

    try {
      await sendNative({
        type: "capture_end",
        captureId: packet.captureId,
        byteLength: packet.byteLength
      });
      await forwardDiagnostic("capture_end_sent");
      acknowledge(packet.captureId, "end");
    } catch (error) {
      try { await forwardDiagnostic("capture_forward_failed"); } catch (_) {}
      throw error;
    }
  }

  // Backward-compatible full-buffer path for older injected interceptors.
  async function forwardCapture(packet) {
    await forwardDiagnostic("capture_received");

    const metadata = packet.metadata;
    if (!metadata || typeof metadata.captureId !== "string") {
      await forwardDiagnostic("capture_metadata_rejected");
      return;
    }

    const bytes = toCaptureBytes(packet.body, metadata.byteLength);
    if (bytes === null) {
      await forwardDiagnostic("capture_body_rejected");
      return;
    }

    await forwardDiagnostic("capture_body_accepted");

    try {
      await sendNative({
        type: "capture_start",
        captureId: metadata.captureId,
        pageUrl: metadata.pageUrl,
        requestUrl: metadata.requestUrl,
        method: metadata.method,
        status: metadata.status,
        contentType: metadata.contentType,
        initiator: metadata.initiator,
        capturedAt: metadata.capturedAt,
        byteLength: metadata.byteLength,
        fidelity: metadata.fidelity
      });
      await forwardDiagnostic("capture_start_sent");

      let sequence = 0;
      for (let offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
        const chunk = bytes.subarray(offset, Math.min(offset + CHUNK_BYTES, bytes.length));
        await forwardDiagnostic("chunk_encode_started");
        const base64 = bytesToBase64(chunk);
        await forwardDiagnostic("chunk_encode_complete");
        await forwardDiagnostic("chunk_send_started");
        await sendNative({
          type: "capture_chunk",
          captureId: metadata.captureId,
          sequence,
          base64
        });
        await forwardDiagnostic("chunk_send_complete");
        sequence += 1;
      }

      await sendNative({
        type: "capture_end",
        captureId: metadata.captureId,
        byteLength: metadata.byteLength
      });
      await forwardDiagnostic("capture_end_sent");
    } catch (error) {
      try { await forwardDiagnostic("capture_forward_failed"); } catch (_) {}
      throw error;
    }
  }

  window.addEventListener("message", event => {
    const packet = event.data;
    if (event.source !== window || !packet || packet.source !== SOURCE) {
      return;
    }

    if (packet.kind === "diagnostic") {
      forwarding = forwarding
        .then(() => forwardDiagnostic(packet.event))
        .catch(error => console.debug("[BKE DNA Android] diagnostic forwarding failed", error));
      return;
    }

    if (packet.kind === "capture_start") {
      forwarding = forwarding.then(() => forwardStreamStart(packet));
    } else if (packet.kind === "capture_chunk") {
      forwarding = forwarding.then(() => forwardStreamChunk(packet));
    } else if (packet.kind === "capture_end") {
      forwarding = forwarding.then(() => forwardStreamEnd(packet));
    } else if (packet.kind === "capture") {
      forwarding = forwarding.then(() => forwardCapture(packet));
    } else {
      return;
    }

    forwarding = forwarding.catch(error => {
      console.debug("[BKE DNA Android] native capture skipped", error);
    });
  });

  injectMainInterceptor();
})();
