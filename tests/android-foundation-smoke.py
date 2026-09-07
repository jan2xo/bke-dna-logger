#!/usr/bin/env python3
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

repo = Path(__file__).resolve().parents[1]
android = repo / "src" / "BKE.Dna.Logger.Platform.Android"
core = repo / "src" / "BKE.Dna.Logger.Core"
host = repo / "src" / "BKE.Dna.Logger.Host"

manifest_path = android / "Assets" / "dna-extension" / "manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

if manifest.get("manifest_version") != 2:
    raise SystemExit("Android GeckoView extension must use the certified MV2 built-in extension contract")

gecko_id = (
    manifest.get("browser_specific_settings", {})
    .get("gecko", {})
    .get("id")
)
if gecko_id != "bke-dna-logger@jl-bke.com":
    raise SystemExit(f"unexpected GeckoView extension id: {gecko_id}")

permissions = set(manifest.get("permissions", []))
required_permissions = {"nativeMessaging", "nativeMessagingFromContent", "geckoViewAddons"}
if not required_permissions.issubset(permissions):
    raise SystemExit(f"Android extension missing permissions: {sorted(required_permissions - permissions)}")

scripts = manifest.get("content_scripts", [])
matched_hosts = {
    match
    for script in scripts
    for match in script.get("matches", [])
}
for required_host in ("https://chatgpt.com/*", "https://chat.openai.com/*"):
    if required_host not in matched_hosts:
        raise SystemExit(f"Android extension missing ChatGPT host match {required_host}")

web_resources = set(manifest.get("web_accessible_resources", []))
if "main-interceptor.js" not in web_resources:
    raise SystemExit("shared MAIN-world interceptor is not web-accessible to the Android bridge")

bridge = (android / "Assets" / "dna-extension" / "bridge.js").read_text(encoding="utf-8")
dom_witness = (android / "Assets" / "dna-extension" / "dom-witness.js").read_text(encoding="utf-8")
main_interceptor = (repo / "extension" / "main-interceptor.js").read_text(encoding="utf-8")

for token in (
    'sendNativeMessage(NATIVE_APP',
    'type: "capture_start"',
    'type: "capture_chunk"',
    'type: "capture_end"',
    '192 * 1024',
    'browser.runtime.getURL("main-interceptor.js")',
):
    if token not in bridge:
        raise SystemExit(f"Android bridge contract missing {token!r}")

for token in (
    'type: "dom_witness"',
    'sendNativeMessage(NATIVE_APP',
):
    if token not in dom_witness:
        raise SystemExit(f"Android DOM witness bridge missing {token!r}")

for token in (
    "response.clone()",
    "clone.arrayBuffer()",
    'fidelity: "browser-application-response-body"',
):
    if token not in main_interceptor:
        raise SystemExit(f"shared interceptor lost response-fidelity marker {token!r}")

for name, content in {
    "android bridge": bridge,
    "android DOM witness": dom_witness,
    "shared MAIN interceptor": main_interceptor,
}.items():
    lowered = content.lower()
    for forbidden in ("authorization", "document.cookie", "request.headers", "cookie:"):
        if forbidden in lowered:
            raise SystemExit(f"{name} contains forbidden auth/header capture token {forbidden!r}")

android_project = ET.parse(android / "BKE.Dna.Logger.Platform.Android.csproj").getroot()
android_xml = ET.tostring(android_project, encoding="unicode")
if "net10.0-android" not in android_xml:
    raise SystemExit("Android host does not target net10.0-android")
if "../BKE.Dna.Logger.Core/BKE.Dna.Logger.Core.csproj" not in android_xml:
    raise SystemExit("Android host does not reference shared DNA core")
if '../../extension/main-interceptor.js' not in android_xml:
    raise SystemExit("Android APK does not link the canonical desktop MAIN interceptor")

host_project = ET.parse(host / "BKE.Dna.Logger.Host.csproj").getroot()
host_xml = ET.tostring(host_project, encoding="unicode")
if "../BKE.Dna.Logger.Core/BKE.Dna.Logger.Core.csproj" not in host_xml:
    raise SystemExit("desktop host does not reference shared DNA core")

wire_protocol = (core / "Protocol" / "DnaWireProtocol.cs").read_text(encoding="utf-8")
for wire_type in ("capture_start", "capture_chunk", "capture_end", "dom_witness"):
    if wire_type not in wire_protocol:
        raise SystemExit(f"shared core is missing wire type {wire_type}")

for retired in (
    host / "Protocol" / "CaptureMessages.cs",
    host / "Protocol" / "DomWitness.cs",
):
    if retired.exists():
        raise SystemExit(f"host-local protocol duplicate still exists: {retired}")

android_manifest = ET.parse(android / "Properties" / "AndroidManifest.xml").getroot()
android_ns = "{http://schemas.android.com/apk/res/android}"
permissions = {
    node.attrib.get(android_ns + "name")
    for node in android_manifest.findall("uses-permission")
}
if "android.permission.INTERNET" not in permissions:
    raise SystemExit("Android app lacks INTERNET permission required for ordinary ChatGPT browsing")

print("android foundation smoke PASS")
