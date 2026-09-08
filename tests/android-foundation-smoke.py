#!/usr/bin/env python3
import json
from pathlib import Path
import xml.etree.ElementTree as ET

repo = Path(__file__).resolve().parents[1]
android = repo / "android"
app = android / "app"
assets = app / "src" / "main" / "assets" / "dna-extension"
host = repo / "src" / "BKE.Dna.Logger.Host"
core = repo / "src" / "BKE.Dna.Logger.Core"

# Android is Kotlin-native. The retired .NET Android and GeckoView binding
# projects must not creep back into the active tree.
for retired in (
    repo / "src" / "BKE.Dna.Logger.Platform.Android" / "BKE.Dna.Logger.Platform.Android.csproj",
    repo / "src" / "BKE.Dna.Logger.GeckoView.Bindings" / "BKE.Dna.Logger.GeckoView.Bindings.csproj",
):
    if retired.exists():
        raise SystemExit(f"retired managed Android project still exists: {retired}")

settings = (android / "settings.gradle.kts").read_text(encoding="utf-8")
root_build = (android / "build.gradle.kts").read_text(encoding="utf-8")
app_build = (app / "build.gradle.kts").read_text(encoding="utf-8")

for token in (
    'id("com.android.application") version "9.1.0"',
    'id("org.jetbrains.kotlin.android") version "2.4.10"',
):
    if token not in root_build:
        raise SystemExit(f"Android toolchain pin missing {token!r}")

for token in (
    'maven("https://maven.mozilla.org/maven2/")',
    'org.mozilla.geckoview:geckoview-arm64-v8a:154.0.20260824154132',
    'compileSdk = 36',
    'minSdk = 26',
    'targetSdk = 36',
    'abiFilters += "arm64-v8a"',
    '../extension/main-interceptor.js',
):
    source = settings if token.startswith('maven(') else app_build
    if token not in source:
        raise SystemExit(f"Android Gradle contract missing {token!r}")

manifest_path = assets / "manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
if manifest.get("manifest_version") != 2:
    raise SystemExit("Android GeckoView extension must use the certified MV2 built-in extension contract")

gecko_id = manifest.get("browser_specific_settings", {}).get("gecko", {}).get("id")
if gecko_id != "bke-dna-logger@jl-bke.com":
    raise SystemExit(f"unexpected GeckoView extension id: {gecko_id}")

permissions = set(manifest.get("permissions", []))
required_permissions = {"nativeMessaging", "nativeMessagingFromContent", "geckoViewAddons"}
if not required_permissions.issubset(permissions):
    raise SystemExit(f"Android extension missing permissions: {sorted(required_permissions - permissions)}")

scripts = manifest.get("content_scripts", [])
matched_hosts = {match for script in scripts for match in script.get("matches", [])}
for required_host in ("https://chatgpt.com/*", "https://chat.openai.com/*"):
    if required_host not in matched_hosts:
        raise SystemExit(f"Android extension missing ChatGPT host match {required_host}")

if "main-interceptor.js" not in set(manifest.get("web_accessible_resources", [])):
    raise SystemExit("shared MAIN-world interceptor is not web-accessible to the Android bridge")

bridge = (assets / "bridge.js").read_text(encoding="utf-8")
dom_witness = (assets / "dom-witness.js").read_text(encoding="utf-8")
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

for token in ('type: "dom_witness"', 'sendNativeMessage(NATIVE_APP'):
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

probe = (app / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger" / "GeckoViewArtifactProbe.kt").read_text(encoding="utf-8")
for api in ("GeckoRuntime", "GeckoSession", "GeckoView", "WebExtension"):
    if api not in probe:
        raise SystemExit(f"Kotlin GeckoView probe does not compile against {api}")

kotlin_wire = (app / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger" / "DnaWireContract.kt").read_text(encoding="utf-8")
wire_protocol = (core / "Protocol" / "DnaWireProtocol.cs").read_text(encoding="utf-8")
for wire_type in ("capture_start", "capture_chunk", "capture_end", "dom_witness"):
    if wire_type not in kotlin_wire:
        raise SystemExit(f"Kotlin Android contract is missing wire type {wire_type}")
    if wire_type not in wire_protocol:
        raise SystemExit(f"desktop .NET contract is missing wire type {wire_type}")

host_project = ET.parse(host / "BKE.Dna.Logger.Host.csproj").getroot()
host_xml = ET.tostring(host_project, encoding="unicode")
if "../BKE.Dna.Logger.Core/BKE.Dna.Logger.Core.csproj" not in host_xml:
    raise SystemExit("desktop host does not reference .NET DNA core")

android_manifest = ET.parse(app / "src" / "main" / "AndroidManifest.xml").getroot()
android_ns = "{http://schemas.android.com/apk/res/android}"
manifest_permissions = {
    node.attrib.get(android_ns + "name")
    for node in android_manifest.findall("uses-permission")
}
if "android.permission.INTERNET" not in manifest_permissions:
    raise SystemExit("Android app lacks INTERNET permission required for ordinary ChatGPT browsing")

ci = (repo / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
for forbidden in ("dotnet workload install android", "BKE.Dna.Logger.GeckoView.Bindings", "net10.0-android"):
    if forbidden in ci:
        raise SystemExit(f"CI still contains retired managed Android path {forbidden!r}")
for required in ("gradle-version: '9.3.1'", "gradle -p android :app:assembleDebug"):
    if required not in ci:
        raise SystemExit(f"CI is missing Kotlin Android build gate {required!r}")

print("android Kotlin foundation smoke PASS")
