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
echo "[1/6] Compiling stubs with javac --release 11..."
STUB_SOURCES=$(find stubs -type f -name "*.java")
javac --release 11 -d "$STUBS_CLASSES_DIR" $STUB_SOURCES

# 2. Compile bridge sources with stubs on the classpath
echo "[2/6] Compiling bridge sources with javac --release 11..."
BRIDGE_SOURCES=$(find src -type f -name "*.java")
javac --release 11 -cp "$STUBS_CLASSES_DIR" -d "$CLASSES_DIR" $BRIDGE_SOURCES

# 3. Package compiled classes into art-bridge.jar
echo "[3/6] Creating build/art-bridge.jar..."
jar cf "$BUILD_DIR/art-bridge.jar" -C "$CLASSES_DIR" .

echo "✓ Created $BUILD_DIR/art-bridge.jar ($(stat -c%s "$BUILD_DIR/art-bridge.jar") bytes)"

# 4. Convert JAR to DEX if d8 or dx is found
echo "[4/6] Checking for DEX compiler (d8 / dx)..."
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

# 5. Run host-JVM self-tests with assertions enabled
echo "[5/6] Running TestDispatcher self-tests (java -ea)..."
java -ea -cp "$STUBS_CLASSES_DIR:$CLASSES_DIR" bridge.TestDispatcher

# 6. Verify Build.VERSION.SDK_INT is not a compile-time constant: use sites
# must emit getstatic, never an inlined bipush/iconst 34.
echo "[6/6] Verifying SDK_INT is not inlined (getstatic check)..."
javap -c -cp "$STUBS_CLASSES_DIR" 'android.os.Build$VERSION' > "$BUILD_DIR/build_version.javap"
javap -c -cp "$CLASSES_DIR" bridge.Dispatcher > "$BUILD_DIR/dispatcher.javap"
if ! grep -q 'getstatic.*Build\$VERSION\.SDK_INT' "$BUILD_DIR/dispatcher.javap"; then
    echo "ERROR: Build.VERSION.SDK_INT access does not compile to getstatic (constant was inlined)" >&2
    exit 1
fi
if grep -q 'bipush.*34' "$BUILD_DIR/dispatcher.javap"; then
    echo "ERROR: found inlined bipush 34 in bridge.Dispatcher (SDK_INT was constant-folded)" >&2
    exit 1
fi
echo "✓ SDK_INT verified non-inlined (getstatic present, no bipush 34)"

echo "=== Build finished successfully! ==="
