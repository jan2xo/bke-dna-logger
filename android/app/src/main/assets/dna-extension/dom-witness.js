(() => {
  "use strict";

  const NATIVE_APP = "bke.dna.logger";
  const MAX_SNIPPETS = 12;
  const MIN_CHARS = 24;
  const MAX_CHARS = 220;
  const DEBOUNCE_MS = 1200;
  let timer = null;
  let lastFingerprint = null;

  function normalize(text) {
    return text.replace(/\s+/g, " ").trim();
  }

  function isVisible(element) {
    if (!(element instanceof Element)) {
      return false;
    }

    if (["SCRIPT", "STYLE", "NOSCRIPT", "TEXTAREA", "INPUT"].includes(element.tagName)) {
      return false;
    }

    const style = getComputedStyle(element);
    if (style.display === "none" || style.visibility === "hidden") {
      return false;
    }

    return element.getClientRects().length > 0;
  }

  function collectSnippets() {
    const root = document.querySelector("main") || document.body;
    if (!root) {
      return [];
    }

    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const snippets = [];
    const seen = new Set();

    while (walker.nextNode() && snippets.length < MAX_SNIPPETS) {
      const node = walker.currentNode;
      const parent = node.parentElement;
      if (!parent || !isVisible(parent)) {
        continue;
      }

      const normalized = normalize(node.textContent || "");
      if (normalized.length < MIN_CHARS) {
        continue;
      }

      const snippet = normalized.slice(0, MAX_CHARS);
      if (seen.has(snippet)) {
        continue;
      }

      seen.add(snippet);
      snippets.push(snippet);
    }

    return snippets;
  }

  async function publishWitness() {
    timer = null;
    const snippets = collectSnippets();
    if (snippets.length === 0) {
      return;
    }

    const fingerprint = `${location.href}\n${snippets.join("\n")}`;
    if (fingerprint === lastFingerprint) {
      return;
    }
    lastFingerprint = fingerprint;

    try {
      await browser.runtime.sendNativeMessage(NATIVE_APP, {
        type: "dom_witness",
        witnessId: crypto.randomUUID(),
        pageUrl: location.href,
        observedAt: new Date().toISOString(),
        snippets
      });
    } catch (error) {
      console.debug("[BKE DNA Android] DOM witness skipped", error);
    }
  }

  function scheduleWitness() {
    if (timer !== null) {
      clearTimeout(timer);
    }
    timer = setTimeout(publishWitness, DEBOUNCE_MS);
  }

  const observer = new MutationObserver(scheduleWitness);
  observer.observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true
  });

  scheduleWitness();
})();
