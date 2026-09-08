(() => {
  "use strict";

  const SOURCE = "bke-dna-logger";
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
    "capture_forward_failed",
    "interceptor_load_error"
  ]);
  const forwardedDiagnostics = new Set();
  let forwarding = Promise.resolve();

  async function sendNative(message) {
    await browser.runtime.sendNativeMessage(NATIVE_APP, message);
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

  async function forwardCapture(packet) {
    await forwardDiagnostic("capture_received");

    const metadata = packet.metadata;
    if (!metadata || typeof metadata.captureId !== "string") {
      await forwardDiagnostic("capture_metadata_rejected");
      return;
    }

    if (!(packet.body instanceof ArrayBuffer)) {
      await forwardDiagnostic("capture_body_rejected");
      return;
    }

    await forwardDiagnostic("capture_body_accepted");
    const bytes = new Uint8Array(packet.body);

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
        await sendNative({
          type: "capture_chunk",
          captureId: metadata.captureId,
          sequence,
          base64: bytesToBase64(chunk)
        });
        sequence += 1;
      }

      await sendNative({
        type: "capture_end",
        captureId: metadata.captureId
      });
    } catch (error) {
      try {
        await forwardDiagnostic("capture_forward_failed");
      } catch (_) {
        // The native channel itself may be the failing boundary.
      }
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

    if (packet.kind !== "capture") {
      return;
    }

    forwarding = forwarding
      .then(() => forwardCapture(packet))
      .catch(error => console.debug("[BKE DNA Android] native capture skipped", error));
  });

  injectMainInterceptor();
})();
