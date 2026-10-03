(() => {
  "use strict";

  const NATIVE_APP = "bke.worker.android";
  const PROTOCOL_VERSION = 1;
  const REPORT_DEBOUNCE_MS = 300;
  const PERIODIC_REPORT_MS = 5000;

  let timer = null;
  let lastStableState = "";

  function findComposer() {
    return (
      document.querySelector('[data-testid="prompt-textarea"]') ||
      document.querySelector('textarea[placeholder*="Message"]') ||
      document.querySelector('[contenteditable="true"][role="textbox"]') ||
      document.querySelector('[contenteditable="true"]')
    );
  }

  function isTurnBusy() {
    return Boolean(
      document.querySelector('[data-testid="stop-button"]') ||
      document.querySelector('button[aria-label*="Stop"]')
    );
  }

  async function report(force = false) {
    const payload = {
      type: "worker_status",
      protocolVersion: PROTOCOL_VERSION,
      observedAt: new Date().toISOString(),
      pageUrl: location.href,
      composerAvailable: Boolean(findComposer()),
      turnBusy: isTurnBusy()
    };

    const stableState = JSON.stringify({
      pageUrl: payload.pageUrl,
      composerAvailable: payload.composerAvailable,
      turnBusy: payload.turnBusy
    });

    if (!force && stableState === lastStableState) {
      return;
    }
    lastStableState = stableState;

    try {
      await browser.runtime.sendNativeMessage(NATIVE_APP, payload);
    } catch (error) {
      console.debug("[BKE Worker Android] status probe skipped", error);
    }
  }

  function scheduleReport() {
    if (timer !== null) {
      clearTimeout(timer);
    }
    timer = setTimeout(() => {
      timer = null;
      void report();
    }, REPORT_DEBOUNCE_MS);
  }

  const observer = new MutationObserver(scheduleReport);
  observer.observe(document.documentElement, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ["aria-busy", "disabled", "data-testid"]
  });

  window.addEventListener("focus", () => void report(true));
  window.addEventListener("popstate", () => void report(true));
  document.addEventListener("visibilitychange", () => void report(true));

  setInterval(() => void report(), PERIODIC_REPORT_MS);
  void report(true);
})();
