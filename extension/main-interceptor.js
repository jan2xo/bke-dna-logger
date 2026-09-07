(() => {
  "use strict";

  const SOURCE = "bke-dna-logger";
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

    const clone = response.clone();
    const body = await clone.arrayBuffer();
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
  }

  window.fetch = async function bkeDnaFetch(...args) {
    const request = resolveRequest(args);
    const response = await originalFetch(...args);

    publishCapture(response, request).catch(error => {
      console.debug("[BKE DNA] capture skipped", error);
    });

    return response;
  };
})();
