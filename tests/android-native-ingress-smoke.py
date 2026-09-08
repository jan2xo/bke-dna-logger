#!/usr/bin/env python3
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
kotlin = repo / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger"
contract = (kotlin / "DnaWireContract.kt").read_text(encoding="utf-8")
ingress = (kotlin / "AndroidWireIngress.kt").read_text(encoding="utf-8")
store = (kotlin / "AndroidCaptureStore.kt").read_text(encoding="utf-8")
index = (kotlin / "AndroidCaptureIndex.kt").read_text(encoding="utf-8")
host = (kotlin / "GeckoViewHost.kt").read_text(encoding="utf-8")
bridge = (repo / "android" / "app" / "src" / "main" / "assets" / "dna-extension" / "bridge.js").read_text(encoding="utf-8")

for token in ("capture_start", "capture_chunk", "capture_end", "MAX_MESSAGE_BYTES"):
    if token not in contract:
        raise SystemExit(f"wire contract missing {token!r}")

for forbidden in ("authorization", "cookie", "requestHeaders", "responseHeaders", "headers"):
    if f'"{forbidden}"' not in contract:
        raise SystemExit(f"wire contract must explicitly reject sensitive field {forbidden!r}")

for token in (
    "AndroidCaptureStore(context.applicationContext)",
    "DnaWireContract.parse(rawMessage)",
    "store.accept(json)",
):
    if token not in ingress:
        raise SystemExit(f"native ingress missing {token!r}")

for token in (
    "MessageDigest.getInstance(\"SHA-256\")",
    "output.fd.sync()",
    "expected sequence",
    "declaredLength",
    "bodies/$bodyName",
    "observations",
    "insertOrThrow",
):
    haystack = store + index
    if token not in haystack:
        raise SystemExit(f"durable Android capture contract missing {token!r}")

if 'dna_archived INTEGER NOT NULL DEFAULT 0' not in index:
    raise SystemExit("SQLite projection must default captures to not archived / not clearable")

for token in (
    "AndroidWireIngress(activity.applicationContext)",
    "message !is JSONObject",
    "ingress.accept(message.toString().toByteArray",
    "ingress.close()",
):
    if token not in host:
        raise SystemExit(f"GeckoView native-message ingress wiring missing {token!r}")

for forbidden in ("Authorization", "Cookie", "requestHeaders", "responseHeaders"):
    if forbidden in bridge:
        raise SystemExit(f"Android bridge must not capture sensitive metadata {forbidden!r}")

print("android native ingress smoke PASS")
