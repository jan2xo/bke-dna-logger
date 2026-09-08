"use strict";

const NATIVE_HOST = "com.bke.dna_logger";
let nativePort = null;

function getNativePort() {
  if (nativePort) {
    return nativePort;
  }

  nativePort = chrome.runtime.connectNative(NATIVE_HOST);
  nativePort.onDisconnect.addListener(() => {
    const message = chrome.runtime.lastError?.message;
    if (message) {
      console.warn("[BKE DNA] native host disconnected:", message);
    }
    nativePort = null;
  });

  return nativePort;
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  try {
    if (!sender.tab?.url || (!sender.tab.url.startsWith("https://chatgpt.com/") && !sender.tab.url.startsWith("https://chat.openai.com/"))) {
      sendResponse({ ok: false, error: "unexpected_sender" });
      return false;
    }

    getNativePort().postMessage(message);
    sendResponse({ ok: true });
  } catch (error) {
    console.error("[BKE DNA] native messaging failure", error);
    sendResponse({ ok: false, error: String(error) });
  }

  return false;
});
