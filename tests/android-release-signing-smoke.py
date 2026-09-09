#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
BUILD = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
WORKFLOW = (ROOT / ".github/workflows/android-release.yml").read_text(encoding="utf-8")


def require(haystack: str, needle: str, where: str) -> None:
    if needle not in haystack:
        raise AssertionError(f"missing {needle!r} in {where}")


def forbid(haystack: str, needle: str, where: str) -> None:
    if needle in haystack:
        raise AssertionError(f"forbidden {needle!r} in {where}")


require(BUILD, 'versionCode = 5', 'android/app/build.gradle.kts')
require(BUILD, 'versionName = "0.1.0-alpha.5"', 'android/app/build.gradle.kts')
require(BUILD, 'create("release")', 'android/app/build.gradle.kts')
require(BUILD, 'isDebuggable = false', 'android/app/build.gradle.kts')
require(BUILD, 'BKE_ANDROID_RELEASE_KEYSTORE_PATH', 'android/app/build.gradle.kts')
require(BUILD, 'BKE_ANDROID_RELEASE_STORE_PASSWORD', 'android/app/build.gradle.kts')
require(BUILD, 'BKE_ANDROID_RELEASE_KEY_ALIAS', 'android/app/build.gradle.kts')
require(BUILD, 'BKE_ANDROID_RELEASE_KEY_PASSWORD', 'android/app/build.gradle.kts')

require(WORKFLOW, 'RELEASE_TAG: android-v0.1.0-alpha.5', '.github/workflows/android-release.yml')
require(WORKFLOW, ':app:assembleRelease', '.github/workflows/android-release.yml')
require(WORKFLOW, 'android/app/build/outputs/apk/release/app-release.apk', '.github/workflows/android-release.yml')
require(WORKFLOW, 'BKE_ANDROID_RELEASE_KEYSTORE_BASE64', '.github/workflows/android-release.yml')
require(WORKFLOW, 'BKE_ANDROID_RELEASE_STORE_PASSWORD', '.github/workflows/android-release.yml')
require(WORKFLOW, 'BKE_ANDROID_RELEASE_KEY_ALIAS', '.github/workflows/android-release.yml')
require(WORKFLOW, 'BKE_ANDROID_RELEASE_KEY_PASSWORD', '.github/workflows/android-release.yml')
require(WORKFLOW, 'apksigner', '.github/workflows/android-release.yml')
require(WORKFLOW, 'CN=Android Debug', '.github/workflows/android-release.yml')
require(WORKFLOW, 'release-signing.txt', '.github/workflows/android-release.yml')
require(WORKFLOW, 'BKE-DNA-Logger-Android-0.1.0-alpha.5.apk', '.github/workflows/android-release.yml')
forbid(WORKFLOW, ':app:assembleDebug', '.github/workflows/android-release.yml')
forbid(WORKFLOW, 'outputs/apk/debug/app-debug.apk', '.github/workflows/android-release.yml')
forbid(WORKFLOW, 'uses Android debug signing', '.github/workflows/android-release.yml')

# Production signing material must remain external to the repository.
for path in ROOT.rglob('*'):
    if not path.is_file():
        continue
    if '.git' in path.parts:
        continue
    lower = path.name.lower()
    if lower.endswith(('.jks', '.keystore', '.p12', '.pfx')):
        raise AssertionError(f"committed signing material is forbidden: {path.relative_to(ROOT)}")

# Keep the release workflow explicit about immutable release behavior.
require(WORKFLOW, 'gh release view "${RELEASE_TAG}"', '.github/workflows/android-release.yml')
require(WORKFLOW, 'leaving the immutable release unchanged', '.github/workflows/android-release.yml')

# A future accidental version drift should fail loudly rather than silently publishing under alpha.5.
version_match = re.search(r'versionName\s*=\s*"([^"]+)"', BUILD)
if not version_match or version_match.group(1) != '0.1.0-alpha.5':
    raise AssertionError('release version drifted from 0.1.0-alpha.5')

print('android production release signing contract smoke PASS')
