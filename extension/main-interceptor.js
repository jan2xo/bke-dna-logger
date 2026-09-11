(() => {
  "use strict";

  const SOURCE = "bke-dna-logger";
  const EXTENSION_SOURCE = "bke-dna-logger-extension";
  const STREAM_CHUNK_BYTES = 128 * 1024;
  const ACK_TIMEOUT_MS = 60 * 1000;
  const HYDRATION_DELAYS_MS = [1_500, 5_000, 15_000];
  const CONVERSATION_ID = /^[A-Za-z0-9][A-Za-z0-9_-]{7,127}$/;
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
    "page_unhandled_rejection",
    "page_window_open",
    "page_history_push_state",
    "page_history_replace_state",
    "page_navigation_api",
    "page_popstate",
    "page_hashchange",
    "hydration_requested",
    "hydration_status_2xx",
    "hydration_status_3xx",
    "hydration_status_4xx",
    "hydration_status_5xx",
    "hydration_status_other",
    "hydration_publish_started",
    "hydration_failed"
  ]);
  const REPEATABLE_DIAGNOSTIC_EVENTS = new Set([
    "page_window_open",
    "page_history_push_state",
    "page_history_replace_state",
    "page_navigation_api",
    "page_popstate",
    "page_hashchange",
    "hydration_requested",
    "hydration_status_2xx",
    "hydration_status_3xx",
    "hydration_status_4xx",
    "hydration_status_5xx",
    "hydration_status_other",
    "hydration_publish_started",
    "hydration_failed"
  ]);
  const emittedDiagnostics = new Set();

  let hydrationGeneration = 0;
  let lastHydrationSeriesId = null;
  const hydrationInFlight = new Set();

  function emitDiagnostic(event) {
    if (!DIAGNOSTIC_EVENTS.has(event)) return;
    if (!REPEATABLE_DIAGNOSTIC_EVENTS.has(event) && emittedDiagnostics.has(event)) return;
    if (!REPEATABLE_DIAGNOSTIC_EVENTS.has(event)) emittedDiagnostics.add(event);
    window.postMessage({ source: SOURCE, kind: "diagnostic", event }, "*");
  }

  function emitHydrationStatus(status) {
    if (status >= 200 && status < 300) {
      emitDiagnostic("hydration_status_2xx");
    } else if (status >= 300 && status < 400) {
      emitDiagnostic("hydration_status_3xx");
    } else if (status >= 400 && status < 500) {
      emitDiagnostic("hydration_status_4xx");
    } else if (status >= 500 && status < 600) {
      emitDiagnostic("hydration_status_5xx");
    } else {
      emitDiagnostic("hydration_status_other");
    }
  }

  function waitForAck(captureId, phase, sequence = null) {
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        window.removeEventListener("message", onMessage);
        reject(new Error(`BKE DNA ${phase} ACK timed out`));
      }, ACK_TIMEOUT_MS);
      function onMessage(event) {
        if (event.source !== window) return;
        const data = event.data;
        if (!data || data.source !== EXTENSION_SOURCE || data.kind !== "capture_ack") return;
        if (data.captureId !== captureId || data.phase !== phase) return;
        if (phase === "chunk" && data.sequence !== sequence) return;
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
    const allowedContentTypes = ["application/json", "application/x-ndjson", "text/event-stream", "text/plain"];

    function shouldCapture(response) {
      const type = (response.headers.get("content-type") || "").toLowerCase();
      return allowedContentTypes.some(candidate => type.includes(candidate));
    }

    function resolveRequest(args) {
      const input = args[0];
      const init = args[1] || {};
      if (input instanceof Request) {
        return { url: input.url, method: String(init.method || input.method || "GET").toUpperCase() };
      }
      return { url: new URL(String(input), window.location.href).href, method: String(init.method || "GET").toUpperCase() };
    }

    function currentConversationId() {
      const segments = window.location.pathname.split("/").filter(Boolean);
      for (let index = 0; index < segments.length - 1; index += 1) {
        if (segments[index] !== "c") continue;
        const candidate = segments[index + 1];
        if (CONVERSATION_ID.test(candidate)) return candidate;
      }
      return null;
    }

    async function hydrateConversation(conversationId) {
      if (!conversationId || hydrationInFlight.has(conversationId)) return;
      hydrationInFlight.add(conversationId);
      const relativeUrl = `/backend-api/conversation/${encodeURIComponent(conversationId)}`;
      const request = {
        url: new URL(relativeUrl, window.location.href).href,
        method: "GET"
      };
      emitDiagnostic("hydration_requested");
      try {
        const response = await originalFetch(relativeUrl, {
          method: "GET",
          credentials: "include",
          cache: "no-store"
        });
        emitHydrationStatus(response.status);
        if (!response.ok) return;
        emitDiagnostic("hydration_publish_started");
        await publishCapture(response, request);
      } catch (error) {
        emitDiagnostic("hydration_failed");
        console.debug("[BKE DNA] canonical conversation hydration skipped", error);
      } finally {
        hydrationInFlight.delete(conversationId);
      }
    }

    function scheduleConversationHydrationSeries(conversationId, force = false) {
      if (!conversationId) return;
      if (!force && lastHydrationSeriesId === conversationId) return;
      lastHydrationSeriesId = conversationId;
      const generation = ++hydrationGeneration;
      HYDRATION_DELAYS_MS.forEach(delay => {
        setTimeout(() => {
          if (generation !== hydrationGeneration) return;
          if (currentConversationId() !== conversationId) return;
          hydrateConversation(conversationId);
        }, delay);
      });
    }

    function scheduleCurrentConversationHydration(force = false) {
      const currentId = currentConversationId();
      if (currentId) scheduleConversationHydrationSeries(currentId, force);
    }

    function hydrateCurrentConversationOnce() {
      const conversationId = currentConversationId();
      if (conversationId) hydrateConversation(conversationId);
    }

    function afterHistoryMutation(previousConversationId) {
      const currentId = currentConversationId();
      if (previousConversationId && previousConversationId !== currentId) {
        hydrateConversation(previousConversationId);
      }
      if (!previousConversationId && currentId) {
        scheduleConversationHydrationSeries(currentId, true);
      }
    }

    const originalWindowOpen = window.open;
    window.open = function bkeDnaWindowOpen(...args) {
      emitDiagnostic("page_window_open");
      return Reflect.apply(originalWindowOpen, this, args);
    };

    const originalPushState = history.pushState;
    history.pushState = function bkeDnaPushState(...args) {
      const previousConversationId = currentConversationId();
      emitDiagnostic("page_history_push_state");
      const result = Reflect.apply(originalPushState, this, args);
      afterHistoryMutation(previousConversationId);
      return result;
    };

    const originalReplaceState = history.replaceState;
    history.replaceState = function bkeDnaReplaceState(...args) {
      const previousConversationId = currentConversationId();
      emitDiagnostic("page_history_replace_state");
      const result = Reflect.apply(originalReplaceState, this, args);
      afterHistoryMutation(previousConversationId);
      return result;
    };

    if (window.navigation && typeof window.navigation.addEventListener === "function") {
      window.navigation.addEventListener("navigate", () => emitDiagnostic("page_navigation_api"));
      window.navigation.addEventListener("navigatesuccess", () => {
        emitDiagnostic("page_navigation_api");
        scheduleCurrentConversationHydration(true);
      });
      window.navigation.addEventListener("currententrychange", () => {
        emitDiagnostic("page_navigation_api");
      });
    }
    window.addEventListener("popstate", () => {
      emitDiagnostic("page_popstate");
      setTimeout(() => scheduleCurrentConversationHydration(), 0);
    });
    window.addEventListener("hashchange", () => emitDiagnostic("page_hashchange"));
    document.addEventListener("submit", () => {
      scheduleCurrentConversationHydration(true);
    }, true);
    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState === "hidden") hydrateCurrentConversationOnce();
    });
    window.addEventListener("pagehide", hydrateCurrentConversationOnce);

    async function publishCapture(response, request) {
      if (!shouldCapture(response)) return;
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
      let captureStarted = false;
      try {
        await postWithAck({ source: SOURCE, kind: "capture_start", metadata }, [], "start");
        captureStarted = true;
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          if (!value || value.byteLength === 0) continue;
          for (let offset = 0; offset < value.byteLength; offset += STREAM_CHUNK_BYTES) {
            const sourceChunk = value.subarray(offset, Math.min(offset + STREAM_CHUNK_BYTES, value.byteLength));
            const chunk = new Uint8Array(sourceChunk.byteLength);
            chunk.set(sourceChunk);
            byteLength += chunk.byteLength;
            await postWithAck({ source: SOURCE, kind: "capture_chunk", captureId, sequence, body: chunk.buffer }, [chunk.buffer], "chunk", sequence);
            sequence += 1;
          }
        }
        await postWithAck({ source: SOURCE, kind: "capture_end", captureId, byteLength }, [], "end");
        captureStarted = false;
      } catch (error) {
        emitDiagnostic("body_read_failed");
        if (captureStarted) {
          try {
            await postWithAck({ source: SOURCE, kind: "capture_abort", captureId }, [], "abort");
            captureStarted = false;
          } catch (_) {
            // Native messaging may already be unavailable; Android store close is the fallback release path.
          }
        }
        try { await reader.cancel(error); } catch (_) {}
        throw error;
      }

      emitDiagnostic("body_read_complete");
      emitDiagnostic("capture_posted");
    }

    window.fetch = async function bkeDnaFetch(...args) {
      emitDiagnostic("fetch_seen");
      const request = resolveRequest(args);
      const response = await originalFetch(...args);
      publishCapture(response, request).catch(error => console.debug("[BKE DNA] capture skipped", error));
      return response;
    };

    emitDiagnostic("interceptor_ready");
  } catch (error) {
    emitDiagnostic("interceptor_load_error");
    console.debug("[BKE DNA] interceptor initialization failed", error);
  }
})();
