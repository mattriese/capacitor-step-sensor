#!/usr/bin/env bash
#
# Stamps PluginBuildInfo.kt with the current git short hash,
# builds the TypeScript, and pushes via yalc.
#
# Usage: bash scripts/yalc-push.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLUGIN_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_INFO_FILE="$PLUGIN_ROOT/android/src/main/java/com/deloreanhovercraft/capacitor/stepsensor/PluginBuildInfo.kt"

cd "$PLUGIN_ROOT"

# Get current git short hash
BUILD_ID=$(git rev-parse --short HEAD)
echo "==> Stamping BUILD_ID = $BUILD_ID"

# Replace the BUILD_ID value in PluginBuildInfo.kt
sed -i '' "s/const val BUILD_ID = \".*\"/const val BUILD_ID = \"$BUILD_ID\"/" "$BUILD_INFO_FILE"

# Build TypeScript
echo "==> Building TypeScript..."
npm run build

# Push via yalc
echo "==> Pushing to yalc..."
npx yalc push --force

# Reset PluginBuildInfo.kt back to "dev" so git stays clean
sed -i '' "s/const val BUILD_ID = \".*\"/const val BUILD_ID = \"dev\"/" "$BUILD_INFO_FILE"

echo ""
echo "==> Done! Plugin pushed with build ID: $BUILD_ID"
echo "    Run 'npm run plugin:sync' in flow-ionic to complete the update."
