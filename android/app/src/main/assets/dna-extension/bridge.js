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
    "interceptor_load_error"
  ]);
  const forwardedDiagnostics = new Set();
  let forwarding = Promise.resolve();

  function bytesToBase64(bytes) {
    let binary = "";
    for (let offset = 0; offset < bytes.length; offset += BINARY_STRING_SLICE) {
      const slice = bytes.subarray(offset, Math.min(offset + BINARY_STRING_SLICE, bytes.length));
      binary += String.fromCharCode.apply(null, slice);
    }
    return btoa(binary);
  }

  async function sendNative(message) {
    await browser.runtime.sendNativeMessage(NATIVE_APP, message);
  }

  function queueDiagnostic(event) {
    if (!DIAGNOSTIC_EVENTS.has(event) || forwardedDiagnostics.has(event)) {
      return;
    }

    forwardedDiagnostics.add(event);
    forwarding = forwarding
      .then(() => sendNative({
        type: "runtime_diagnostic",
        event
      }))
      .catch(error => console.debug("[BKE DNA Android] runtime diagnostic skipped", error));
  }

  function injectMainInterceptor() {
    const script = document.createElement("script");
    script.src = browser.runtime.getURL("main-interceptor.js");
    script.async = false;
    script.addEventListener("load", () => script.remove(), { once: true });
    script.addEventListener("error", () => {
      script.remove();
      queueDiagnostic("interceptor_load_error");
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

  async function forwardCapture(packet) {
    const metadata = packet.metadata;
    if (!metadata || typeof metadata.captureId !== "string" || !(packet.body instanceof ArrayBuffer)) {
      return;
    }

    const bytes = new Uint8Array(packet.body);
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
  }

  window.addEventListener("message", event => {
    const packet = event.data;
    if (event.source !== window || !packet || packet.source !== SOURCE) {
      return;
    }

    if (packet.kind === "runtime_diagnostic") {
      queueDiagnostic(packet.event);
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
