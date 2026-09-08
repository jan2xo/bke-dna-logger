#!/usr/bin/env python3
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
kotlin = repo / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger"
manifest = (repo / "android" / "app" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")
main = (kotlin / "MainActivity.kt").read_text(encoding="utf-8")
provider = (kotlin / "GeckoRuntimeProvider.kt").read_text(encoding="utf-8")
host = (kotlin / "GeckoViewHost.kt").read_text(encoding="utf-8")

for token in (
    "GeckoView(this)",
    "GeckoViewHost(this, geckoView)",
    "geckoHost.start()",
    "geckoHost.stop()",
):
    if token not in main:
        raise SystemExit(f"MainActivity is missing GeckoView runtime contract {token!r}")

for token in (
    "private var instance: GeckoRuntime? = null",
    "GeckoRuntime.create(context.applicationContext)",
    "synchronized(this)",
):
    if token not in provider:
        raise SystemExit(f"process-lifetime GeckoRuntime guard missing {token!r}")

if "GeckoRuntime.create" in host:
    raise SystemExit("GeckoViewHost must obtain the process runtime from GeckoRuntimeProvider")

for token in (
    "GeckoSession()",
    "session.open(runtime)",
    "view.setSession(session)",
    'EXTENSION_URI = "resource://android/assets/dna-extension/"',
    'EXTENSION_ID = "bke-dna-logger@jl-bke.com"',
    'NATIVE_APP = "bke.dna.logger"',
    "ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)",
    "setMessageDelegate(",
    'CHATGPT_URL = "https://chatgpt.com/"',
    "session.loadUri(CHATGPT_URL)",
    "view.releaseSession()",
    "session.close()",
):
    if token not in host:
        raise SystemExit(f"GeckoView host contract missing {token!r}")

if host.index("ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)") > host.index("session.loadUri(CHATGPT_URL)"):
    raise SystemExit("ChatGPT navigation must not begin before built-in DNA extension registration")

if 'android:windowSoftInputMode="stateUnspecified|adjustResize"' not in manifest:
    raise SystemExit("GeckoView activity must resize for the Android soft keyboard")

print("android GeckoView runtime smoke PASS")
