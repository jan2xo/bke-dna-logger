(() => {
  "use strict";

  // Disposable A/B build for the Projects -> Show more failure.
  // Deliberately leaves window.fetch completely native so we can determine
  // whether DNA's fetch wrapper/response clone affects ChatGPT's post-fetch UI.
  // This branch is diagnostic-only and must not be merged.
  const SOURCE = "bke-dna-logger";
  const DIAGNOSTIC_EVENTS = new Set([
    "interceptor_ready",
    "page_runtime_error",
    "page_unhandled_rejection",
    "page_window_open",
    "page_history_push_state",
    "page_history_replace_state",
    "page_navigation_api",
    "page_popstate",
    "page_hashchange"
  ]);

  function emitDiagnostic(event) {
    if (!DIAGNOSTIC_EVENTS.has(event)) return;
    window.postMessage({ source: SOURCE, kind: "diagnostic", event }, "*");
  }

  try {
    window.addEventListener("error", () => emitDiagnostic("page_runtime_error"), true);
    window.addEventListener("unhandledrejection", () => emitDiagnostic("page_unhandled_rejection"));

    const originalWindowOpen = window.open;
    window.open = function bkeDnaWindowOpen(...args) {
      emitDiagnostic("page_window_open");
      return Reflect.apply(originalWindowOpen, this, args);
    };

    const originalPushState = history.pushState;
    history.pushState = function bkeDnaPushState(...args) {
      emitDiagnostic("page_history_push_state");
      return Reflect.apply(originalPushState, this, args);
    };

    const originalReplaceState = history.replaceState;
    history.replaceState = function bkeDnaReplaceState(...args) {
      emitDiagnostic("page_history_replace_state");
      return Reflect.apply(originalReplaceState, this, args);
    };

    if (window.navigation && typeof window.navigation.addEventListener === "function") {
      window.navigation.addEventListener("navigate", () => emitDiagnostic("page_navigation_api"));
    }
    window.addEventListener("popstate", () => emitDiagnostic("page_popstate"));
    window.addEventListener("hashchange", () => emitDiagnostic("page_hashchange"));

    // CRITICAL A/B CONDITION: do not read, bind, replace, clone, or wrap window.fetch.
    emitDiagnostic("interceptor_ready");
  } catch (error) {
    emitDiagnostic("page_runtime_error");
    console.debug("[BKE DNA] diagnostic interceptor initialization failed", error);
  }
})();
