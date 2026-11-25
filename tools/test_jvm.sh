#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

TEST_CLASSES="$ROOT_DIR/build/test-classes"
rm -rf "$TEST_CLASSES"
mkdir -p "$TEST_CLASSES"

echo "[1/9] Compiling protocol, Base64Codec, Sherpa-ONNX JNI bindings, Software PBFDAF AEC, MediaPlaybackBufferPolicy, I18n & JVM tests..."
javac --release 11 \
  -encoding UTF-8 \
  -d "$TEST_CLASSES" \
  "$ROOT_DIR"/app/src/main/java/com/k2fsa/sherpa/onnx/*.java \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/Base64Codec.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/BoundedTwoTierQueue.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/MiniJson.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/AcousticGuard.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/SoftwareAecFilter.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/MediaPlaybackBufferPolicy.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/PlaybackStreamDriver.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/LocalKwsEngine.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/CoreDiscovery.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/CoreApiClient.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/TranscriptStateStore.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/ReleaseUpdater.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/DeviceRoutingPolicy.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/protocol/NorthboundSession.java" \
  "$ROOT_DIR/app/src/main/java/homes/mymate/gateway/I18n.java" \
  "$ROOT_DIR/app/src/test/java/homes/mymate/gateway/protocol/NorthboundSessionTest.java" \
  "$ROOT_DIR/app/src/test/java/homes/mymate/gateway/protocol/SoftwareAecFilterTest.java" \
  "$ROOT_DIR/app/src/test/java/homes/mymate/gateway/protocol/CoreApiClientTest.java" \
  "$ROOT_DIR/app/src/test/java/homes/mymate/gateway/protocol/FinalAuditRemediationTest.java" \
  "$ROOT_DIR/app/src/test/java/homes/mymate/gateway/protocol/V2312ScanAndMediaBufferTest.java" \
  "$ROOT_DIR/app/src/test/java/homes/mymate/gateway/Phase3UnitTest.java" \
  "$ROOT_DIR/app/src/test/java/homes/mymate/gateway/V2OverhaulTest.java"

echo "[2/9] Running NorthboundSessionTest..."
java -cp "$TEST_CLASSES" homes.mymate.gateway.protocol.NorthboundSessionTest

echo "[3/9] Running SoftwareAecFilterTest..."
java -cp "$TEST_CLASSES" homes.mymate.gateway.protocol.SoftwareAecFilterTest

echo "[4/9] Running CoreApiClientTest..."
java -cp "$TEST_CLASSES" homes.mymate.gateway.protocol.CoreApiClientTest

echo "[5/9] Running Phase3UnitTest..."
java -cp "$TEST_CLASSES" homes.mymate.gateway.Phase3UnitTest

echo "[6/9] Running V2OverhaulTest..."
java -cp "$TEST_CLASSES" homes.mymate.gateway.V2OverhaulTest

echo "[7/9] Running FinalAuditRemediationTest (P1-1, P1-2, P1-3, P1-5, P2-1, P2)..."
java -cp "$TEST_CLASSES" homes.mymate.gateway.protocol.FinalAuditRemediationTest

echo "[8/9] Running V2312ScanAndMediaBufferTest (LAN scan parity + media prebuffer policy)..."
java -cp "$TEST_CLASSES" homes.mymate.gateway.protocol.V2312ScanAndMediaBufferTest

echo "[9/9] Running P0-1 build_apk.sh --release signing guard self-check..."
SIGN_TMP="$(mktemp -d)"
trap 'rm -rf "$SIGN_TMP"' EXIT
touch "$SIGN_TMP/prod.keystore"
printf '%s\n' "prod-ks-secret-2026" > "$SIGN_TMP/ks.pass"
printf '%s\n' "prod-key-secret-2026" > "$SIGN_TMP/key.pass"

expect_fail() {
  local desc="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    echo "FAIL: Expected failure for: $desc" >&2
    exit 1
  fi
}

# 1. --release with no signing env vars MUST hard-fail
expect_fail "--release without env vars" \
  env -u HM_ANDROID_KEYSTORE -u HM_ANDROID_KEY_ALIAS -u HM_ANDROID_KEYSTORE_PASS -u HM_ANDROID_KEY_PASS \
      -u HM_ANDROID_KEYSTORE_PASS_FILE -u HM_ANDROID_KEY_PASS_FILE -u HM_RELEASE_BUILD \
      bash "$ROOT_DIR/tools/build_apk.sh" --release --validate-signing

# 2. HM_RELEASE_BUILD=1 without env vars MUST hard-fail
expect_fail "HM_RELEASE_BUILD=1 without env vars" \
  env -u HM_ANDROID_KEYSTORE -u HM_ANDROID_KEY_ALIAS -u HM_ANDROID_KEYSTORE_PASS -u HM_ANDROID_KEY_PASS \
      -u HM_ANDROID_KEYSTORE_PASS_FILE -u HM_ANDROID_KEY_PASS_FILE HM_RELEASE_BUILD=1 \
      bash "$ROOT_DIR/tools/build_apk.sh" --validate-signing

# 3. --release with androiddebugkey alias MUST hard-fail
expect_fail "--release with androiddebugkey alias" \
  env HM_ANDROID_KEYSTORE="$SIGN_TMP/prod.keystore" HM_ANDROID_KEY_ALIAS="androiddebugkey" \
      HM_ANDROID_KEYSTORE_PASS_FILE="$SIGN_TMP/ks.pass" HM_ANDROID_KEY_PASS_FILE="$SIGN_TMP/key.pass" \
      bash "$ROOT_DIR/tools/build_apk.sh" --release --validate-signing

# 4. --release with default 'android' keystore password MUST hard-fail
expect_fail "--release with default android keystore password" \
  env HM_ANDROID_KEYSTORE="$SIGN_TMP/prod.keystore" HM_ANDROID_KEY_ALIAS="homemate-prod" \
      HM_ANDROID_KEYSTORE_PASS="android" HM_ANDROID_KEY_PASS_FILE="$SIGN_TMP/key.pass" \
      bash "$ROOT_DIR/tools/build_apk.sh" --release --validate-signing

# 5. --release with default 'android' key password MUST hard-fail
expect_fail "--release with default android key password" \
  env HM_ANDROID_KEYSTORE="$SIGN_TMP/prod.keystore" HM_ANDROID_KEY_ALIAS="homemate-prod" \
      HM_ANDROID_KEYSTORE_PASS_FILE="$SIGN_TMP/ks.pass" HM_ANDROID_KEY_PASS="android" \
      bash "$ROOT_DIR/tools/build_apk.sh" --release --validate-signing

# 6. --release with missing keystore file MUST hard-fail
expect_fail "--release with missing keystore file" \
  env HM_ANDROID_KEYSTORE="$SIGN_TMP/missing.keystore" HM_ANDROID_KEY_ALIAS="homemate-prod" \
      HM_ANDROID_KEYSTORE_PASS_FILE="$SIGN_TMP/ks.pass" HM_ANDROID_KEY_PASS_FILE="$SIGN_TMP/key.pass" \
      bash "$ROOT_DIR/tools/build_apk.sh" --release --validate-signing

# 7. --release with explicit prod keystore, non-debug alias, and file:/env: secrets MUST succeed without leaking secrets
RELEASE_OUT="$(
  env HM_ANDROID_KEYSTORE="$SIGN_TMP/prod.keystore" \
      HM_ANDROID_KEY_ALIAS="homemate-prod" \
      HM_ANDROID_KEYSTORE_PASS_FILE="$SIGN_TMP/ks.pass" \
      MY_PROD_KEY_SECRET="super-secret-key-2026" \
      HM_ANDROID_KEY_PASS="env:MY_PROD_KEY_SECRET" \
      bash "$ROOT_DIR/tools/build_apk.sh" --release --validate-signing
)"
if [[ "$RELEASE_OUT" == *"super-secret-key-2026"* || "$RELEASE_OUT" == *"prod-ks-secret-2026"* ]]; then
  echo "FAIL: Signing validation leaked secret value in output" >&2
  exit 1
fi

# 8. Default dev/LAN mode (no --release) preserves backward-compatible local signing fallback
env -u HM_ANDROID_KEYSTORE -u HM_ANDROID_KEY_ALIAS -u HM_ANDROID_KEYSTORE_PASS -u HM_ANDROID_KEY_PASS \
    -u HM_ANDROID_KEYSTORE_PASS_FILE -u HM_ANDROID_KEY_PASS_FILE -u HM_RELEASE_BUILD \
    bash "$ROOT_DIR/tools/build_apk.sh" --validate-signing >/dev/null

echo "PASS: P0-1 build_apk.sh signing guard self-check passed."
