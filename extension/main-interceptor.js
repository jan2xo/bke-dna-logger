(() => {
  "use strict";

  const SOURCE = "bke-dna-logger";
  const DIAGNOSTIC_EVENTS = new Set([
    "interceptor_ready",
    "fetch_seen",
    "capture_candidate",
    "body_read_started",
    "body_read_complete",
    "body_read_failed",
    "capture_posted",
    "interceptor_load_error"
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

  try {
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

      let body;
      try {
        const clone = response.clone();
        body = await clone.arrayBuffer();
      } catch (error) {
        emitDiagnostic("body_read_failed");
        throw error;
      }

      emitDiagnostic("body_read_complete");
      const captureId = crypto.randomUUID();

      window.postMessage({
        source: SOURCE,
        kind: "capture",
        metadata: {
          captureId,
          pageUrl: window.location.href,
          requestUrl: request.url,
          method: request.method,
          status: response.status,
          contentType: response.headers.get("content-type"),
          initiator: "fetch",
          capturedAt: new Date().toISOString(),
          byteLength: body.byteLength,
          fidelity: "browser-application-response-body"
        },
        body
      }, "*", [body]);

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
