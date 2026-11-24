#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

TEST_CLASSES="$ROOT_DIR/build/test-classes"
rm -rf "$TEST_CLASSES"
mkdir -p "$TEST_CLASSES"

echo "[1/4] Compiling protocol, Base64Codec, Sherpa-ONNX JNI bindings, Software NLMS AEC, I18n & JVM tests..."
javac --release 11 \
  -encoding UTF-8 \
  -d "$TEST_CLASSES" \
  "$ROOT_DIR"/app/src/main/java/com/k2fsa/sherpa/onnx/*.java \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/Base64Codec.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/MiniJson.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/AcousticGuard.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/SoftwareAecFilter.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/LocalKwsEngine.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/CoreDiscovery.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/CoreApiClient.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/NorthboundSession.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/I18n.java" \
  "$ROOT_DIR/app/src/test/java/homes/mymate/gateway/protocol/NorthboundSessionTest.java" \
  "$ROOT_DIR/app/src/test/java/homes/mymate/gateway/Phase3UnitTest.java" \
  "$ROOT_DIR/app/src/test/java/homes/mymate/gateway/V2OverhaulTest.java"

echo "[2/4] Running NorthboundSessionTest..."
java -cp "$TEST_CLASSES" homes.mymate.gateway.protocol.NorthboundSessionTest

echo "[3/4] Running Phase3UnitTest..."
java -cp "$TEST_CLASSES" homes.mymate.gateway.Phase3UnitTest

echo "[4/4] Running V2OverhaulTest..."
java -cp "$TEST_CLASSES" homes.mymate.gateway.V2OverhaulTest
