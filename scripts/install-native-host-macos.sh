#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <chrome-extension-id>" >&2
  exit 64
fi

EXTENSION_ID="$1"
if [[ ! "$EXTENSION_ID" =~ ^[a-p]{32}$ ]]; then
  echo "Chrome extension ID must be 32 characters in the range a-p." >&2
  exit 64
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PUBLISH_DIR="$HOME/.bke/dna-logger/native-host"
MANIFEST_DIR="$HOME/Library/Application Support/Google/Chrome/NativeMessagingHosts"
MANIFEST_PATH="$MANIFEST_DIR/com.bke.dna_logger.json"

mkdir -p "$PUBLISH_DIR" "$MANIFEST_DIR"

dotnet publish "$REPO_ROOT/src/BKE.Dna.Logger.Host/BKE.Dna.Logger.Host.csproj" \
  --configuration Release \
  --output "$PUBLISH_DIR"

HOST_PATH="$PUBLISH_DIR/BKE.Dna.Logger.Host"
chmod +x "$HOST_PATH"

cat > "$MANIFEST_PATH" <<EOF
{
  "name": "com.bke.dna_logger",
  "description": "BKE DNA Logger POC-0 native evidence host",
  "path": "$HOST_PATH",
  "type": "stdio",
  "allowed_origins": [
    "chrome-extension://$EXTENSION_ID/"
  ]
}
EOF

echo "Installed BKE DNA native host manifest: $MANIFEST_PATH"
echo "Capture root defaults to: $HOME/.local/share/BKE/DNA Logger/captures (runtime-dependent LocalApplicationData path)."
