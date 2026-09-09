#!/usr/bin/env python3
import argparse
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import zipfile

MIB = 1024 * 1024
EXPECTED_ABI = "arm64-v8a"
DEX_RE = re.compile(r"^classes(?:\d+)?\.dex$")


def category_for(path: str) -> str:
    if path.startswith("lib/") and path.endswith(".so"):
        return "native_libs"
    if DEX_RE.match(path):
        return "dex"
    if path.startswith("assets/"):
        return "assets"
    if path == "resources.arsc" or path.startswith("res/"):
        return "resources"
    if path == "AndroidManifest.xml":
        return "manifest"
    if path.startswith("META-INF/"):
        return "signing_metadata"
    return "other"


def inspect_apk(apk: Path) -> dict:
    if not apk.is_file():
        raise ValueError(f"APK does not exist: {apk}")

    categories = {}
    largest = []
    abis = set()
    native_entries = []

    with zipfile.ZipFile(apk) as archive:
        infos = [info for info in archive.infolist() if not info.is_dir()]
        for info in infos:
            category = category_for(info.filename)
            bucket = categories.setdefault(
                category,
                {"compressed_bytes": 0, "uncompressed_bytes": 0, "entries": 0},
            )
            bucket["compressed_bytes"] += info.compress_size
            bucket["uncompressed_bytes"] += info.file_size
            bucket["entries"] += 1

            largest.append(
                {
                    "path": info.filename,
                    "compressed_bytes": info.compress_size,
                    "uncompressed_bytes": info.file_size,
                    "category": category,
                }
            )

            if category == "native_libs":
                parts = info.filename.split("/")
                if len(parts) >= 3:
                    abis.add(parts[1])
                native_entries.append(info.filename)

    largest.sort(key=lambda item: item["compressed_bytes"], reverse=True)
    apk_bytes = apk.stat().st_size
    native_bytes = categories.get("native_libs", {}).get("compressed_bytes", 0)
    non_native_bytes = max(0, apk_bytes - native_bytes)

    return {
        "apk": str(apk),
        "apk_bytes": apk_bytes,
        "apk_mib": apk_bytes / MIB,
        "entry_count": sum(bucket["entries"] for bucket in categories.values()),
        "abis": sorted(abis),
        "arm64_only": abis == {EXPECTED_ABI},
        "native_compressed_bytes": native_bytes,
        "native_share_percent": (native_bytes / apk_bytes * 100.0) if apk_bytes else 0.0,
        "non_native_remainder_bytes": non_native_bytes,
        "categories": dict(sorted(categories.items())),
        "largest_entries": largest[:15],
        "native_entries": sorted(native_entries),
    }


def format_mib(value: int) -> str:
    return f"{value / MIB:.2f} MiB"


def markdown_report(report: dict) -> str:
    lines = [
        "## Android APK size audit",
        "",
        f"- APK: `{report['apk']}`",
        f"- Total APK: **{report['apk_mib']:.2f} MiB** ({report['apk_bytes']} bytes)",
        f"- Packaged ABIs: `{', '.join(report['abis']) if report['abis'] else 'none'}`",
        f"- arm64-only: **{'YES' if report['arm64_only'] else 'NO'}**",
        f"- Native library ZIP payload: **{format_mib(report['native_compressed_bytes'])}** ({report['native_share_percent']:.1f}% of APK)",
        f"- Non-native APK remainder: **{format_mib(report['non_native_remainder_bytes'])}**",
        "",
        "### ZIP contribution by category",
        "",
        "| Category | Entries | Compressed | Uncompressed |",
        "| --- | ---: | ---: | ---: |",
    ]
    for name, bucket in report["categories"].items():
        lines.append(
            f"| {name} | {bucket['entries']} | {format_mib(bucket['compressed_bytes'])} | {format_mib(bucket['uncompressed_bytes'])} |"
        )

    lines.extend(
        [
            "",
            "### Largest packaged entries",
            "",
            "| Entry | Category | Compressed | Uncompressed |",
            "| --- | --- | ---: | ---: |",
        ]
    )
    for item in report["largest_entries"]:
        lines.append(
            f"| `{item['path']}` | {item['category']} | {format_mib(item['compressed_bytes'])} | {format_mib(item['uncompressed_bytes'])} |"
        )
    return "\n".join(lines) + "\n"


def emit_report(report: dict, json_out: Path | None) -> None:
    text = markdown_report(report)
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(text)
    if json_out:
        json_out.parent.mkdir(parents=True, exist_ok=True)
        json_out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def self_test() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        apk = Path(tmp) / "synthetic.apk"
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("lib/arm64-v8a/libxul.so", b"x" * 4096)
            archive.writestr("classes.dex", b"d" * 2048)
            archive.writestr("assets/dna-extension/main-interceptor.js", b"a" * 1024)
            archive.writestr("res/layout/example.xml", b"r" * 512)
            archive.writestr("resources.arsc", b"z" * 256)
            archive.writestr("AndroidManifest.xml", b"m" * 128)
            archive.writestr("META-INF/CERT.SF", b"s" * 64)
            archive.writestr("kotlin/example.kotlin_builtins", b"o" * 32)

        report = inspect_apk(apk)
        assert report["arm64_only"]
        assert report["abis"] == [EXPECTED_ABI]
        for category in [
            "native_libs",
            "dex",
            "assets",
            "resources",
            "manifest",
            "signing_metadata",
            "other",
        ]:
            assert category in report["categories"], category
        assert report["categories"]["native_libs"]["entries"] == 1
        assert report["largest_entries"]
    print("android APK size audit self-test PASS")


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit BKE DNA Logger Android APK size composition")
    parser.add_argument("apk", nargs="?", type=Path)
    parser.add_argument("--json-out", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0
    if args.apk is None:
        parser.error("apk path is required unless --self-test is used")

    try:
        report = inspect_apk(args.apk)
    except (ValueError, zipfile.BadZipFile) as error:
        print(f"APK size audit failed: {error}", file=sys.stderr)
        return 2

    emit_report(report, args.json_out)
    if not report["arm64_only"]:
        print(
            f"APK size audit failed: expected only {EXPECTED_ABI}, found {report['abis']}",
            file=sys.stderr,
        )
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
