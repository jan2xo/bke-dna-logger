(() => {
  "use strict";

  const SOURCE = "bke-dna-logger";
  const EXTENSION_SOURCE = "bke-dna-logger-extension";
  const STREAM_CHUNK_BYTES = 128 * 1024;
  const ACK_TIMEOUT_MS = 60 * 1000;
  const DIAGNOSTIC_EVENTS = new Set([
    "interceptor_ready",
    "fetch_seen",
    "capture_candidate",
    "body_read_started",
    "body_read_complete",
    "body_read_failed",
    "capture_posted",
    "interceptor_load_error",
    "page_runtime_error",
    "page_unhandled_rejection"
  ]);
  const emittedDiagnostics = new Set();

  function emitDiagnostic(event) {
    if (!DIAGNOSTIC_EVENTS.has(event) || emittedDiagnostics.has(event)) {
      return;
    }

    emittedDiagnostics.add(event);
    window.postMessage({
      source: SOURCE,
      kind: "diagnostic",
      event
    }, "*");
  }

  function waitForAck(captureId, phase, sequence = null) {
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        window.removeEventListener("message", onMessage);
        reject(new Error(`BKE DNA ${phase} ACK timed out`));
      }, ACK_TIMEOUT_MS);

      function onMessage(event) {
        if (event.source !== window) {
          return;
        }
        const data = event.data;
        if (!data || data.source !== EXTENSION_SOURCE || data.kind !== "capture_ack") {
          return;
        }
        if (data.captureId !== captureId || data.phase !== phase) {
          return;
        }
        if (phase === "chunk" && data.sequence !== sequence) {
          return;
        }

        clearTimeout(timeout);
        window.removeEventListener("message", onMessage);
        resolve();
      }

      window.addEventListener("message", onMessage);
    });
  }

  async function postWithAck(packet, transfer, phase, sequence = null) {
    const ack = waitForAck(packet.captureId || packet.metadata?.captureId, phase, sequence);
    window.postMessage(packet, "*", transfer);
    await ack;
  }

  try {
    window.addEventListener("error", () => emitDiagnostic("page_runtime_error"), true);
    window.addEventListener("unhandledrejection", () => emitDiagnostic("page_unhandled_rejection"));

    const originalFetch = window.fetch.bind(window);

    const allowedContentTypes = [
      "application/json",
      "application/x-ndjson",
      "text/event-stream",
      "text/plain"
    ];

    function shouldCapture(response) {
      const type = (response.headers.get("content-type") || "").toLowerCase();
      return allowedContentTypes.some(candidate => type.includes(candidate));
    }

    function resolveRequest(args) {
      const input = args[0];
      const init = args[1] || {};

      if (input instanceof Request) {
        return {
          url: input.url,
          method: String(init.method || input.method || "GET").toUpperCase()
        };
      }

      return {
        url: new URL(String(input), window.location.href).href,
        method: String(init.method || "GET").toUpperCase()
      };
    }

    async function publishCapture(response, request) {
      if (!shouldCapture(response)) {
        return;
      }

      emitDiagnostic("capture_candidate");
      emitDiagnostic("body_read_started");

      const captureId = crypto.randomUUID();
      const clone = response.clone();
      const reader = clone.body?.getReader();
      if (!reader) {
        emitDiagnostic("body_read_failed");
        return;
      }

      const metadata = {
        captureId,
        pageUrl: window.location.href,
        requestUrl: request.url,
        method: request.method,
        status: response.status,
        contentType: response.headers.get("content-type"),
        initiator: "fetch",
        capturedAt: new Date().toISOString(),
        fidelity: "browser-application-response-body"
      };

      let sequence = 0;
      let byteLength = 0;

      try {
        await postWithAck({
          source: SOURCE,
          kind: "capture_start",
          metadata
        }, [], "start");

        while (true) {
          const { value, done } = await reader.read();
          if (done) {
            break;
          }
          if (!value || value.byteLength === 0) {
            continue;
          }

          for (let offset = 0; offset < value.byteLength; offset += STREAM_CHUNK_BYTES) {
            const sourceChunk = value.subarray(offset, Math.min(offset + STREAM_CHUNK_BYTES, value.byteLength));
            const chunk = new Uint8Array(sourceChunk.byteLength);
            chunk.set(sourceChunk);
            byteLength += chunk.byteLength;

            await postWithAck({
              source: SOURCE,
              kind: "capture_chunk",
              captureId,
              sequence,
              body: chunk.buffer
            }, [chunk.buffer], "chunk", sequence);
            sequence += 1;
          }
        }

        await postWithAck({
          source: SOURCE,
          kind: "capture_end",
          captureId,
          byteLength
        }, [], "end");
      } catch (error) {
        emitDiagnostic("body_read_failed");
        try {
          await reader.cancel(error);
        } catch (_) {
          // The cloned stream may already be closed after a forwarding failure.
        }
        throw error;
      }

      emitDiagnostic("body_read_complete");
      emitDiagnostic("capture_posted");
    }

    window.fetch = async function bkeDnaFetch(...args) {
      emitDiagnostic("fetch_seen");

      const request = resolveRequest(args);
      const response = await originalFetch(...args);

      publishCapture(response, request).catch(error => {
        console.debug("[BKE DNA] capture skipped", error);
      });

      return response;
    };

    emitDiagnostic("interceptor_ready");
  } catch (error) {
    emitDiagnostic("interceptor_load_error");
    console.debug("[BKE DNA] interceptor initialization failed", error);
  }
})();