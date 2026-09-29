#!/usr/bin/env bash
set -euo pipefail

# Directory of this script
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Building Java app_process Worker (art-bridge) ==="

BUILD_DIR="$SCRIPT_DIR/build"
STUBS_CLASSES_DIR="$BUILD_DIR/stubs_classes"
CLASSES_DIR="$BUILD_DIR/classes"

rm -rf "$BUILD_DIR"
mkdir -p "$STUBS_CLASSES_DIR" "$CLASSES_DIR"

# 1. Compile compile-only stubs
echo "[1/4] Compiling stubs with javac --release 11..."
STUB_SOURCES=$(find stubs -type f -name "*.java")
javac --release 11 -d "$STUBS_CLASSES_DIR" $STUB_SOURCES

# 2. Compile bridge sources with stubs on the classpath
echo "[2/4] Compiling bridge sources with javac --release 11..."
BRIDGE_SOURCES=$(find src -type f -name "*.java")
javac --release 11 -cp "$STUBS_CLASSES_DIR" -d "$CLASSES_DIR" $BRIDGE_SOURCES

# 3. Package compiled classes into art-bridge.jar
echo "[3/4] Creating build/art-bridge.jar..."
jar cf "$BUILD_DIR/art-bridge.jar" -C "$CLASSES_DIR" .

echo "✓ Created $BUILD_DIR/art-bridge.jar ($(stat -c%s "$BUILD_DIR/art-bridge.jar") bytes)"

# 4. Convert JAR to DEX if d8 or dx is found
echo "[4/4] Checking for DEX compiler (d8 / dx)..."
if command -v d8 >/dev/null 2>&1; then
    echo "Converting JAR to DEX using d8..."
    d8 --release --min-api 26 --output "$BUILD_DIR/" "$BUILD_DIR/art-bridge.jar"
    if [ -f "$BUILD_DIR/classes.dex" ]; then
        cp "$BUILD_DIR/classes.dex" "$BUILD_DIR/art-bridge.dex"
        echo "✓ Created $BUILD_DIR/art-bridge.dex ($(stat -c%s "$BUILD_DIR/art-bridge.dex") bytes)"
    fi
elif command -v dx >/dev/null 2>&1; then
    echo "Converting JAR to DEX using dx..."
    dx --dex --output="$BUILD_DIR/art-bridge.dex" "$BUILD_DIR/art-bridge.jar"
    echo "✓ Created $BUILD_DIR/art-bridge.dex ($(stat -c%s "$BUILD_DIR/art-bridge.dex") bytes)"
else
    echo "Notice: Neither d8 nor dx found on PATH. Skipping DEX compilation."
fi

if [ ! -f "$BUILD_DIR/art-bridge.dex" ]; then
    echo "ERROR: DEX not produced" >&2
    exit 1
fi

echo "=== Build finished successfully! ==="
