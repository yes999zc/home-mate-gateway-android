#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BUILD_TOOLS_VERSION="${ANDROID_BUILD_TOOLS_VERSION:-35.0.0}"
PLATFORM_VERSION="${ANDROID_PLATFORM_VERSION:-android-35}"

AAPT2="$ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION/aapt2"
D8="$ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION/d8"
ZIPALIGN="$ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION/zipalign"
APKSIGNER="$ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION/apksigner"
ANDROID_JAR="$ANDROID_SDK/platforms/$PLATFORM_VERSION/android.jar"

if [[ ! -f "$ANDROID_JAR" ]]; then
  echo "ERROR: android.jar not found at $ANDROID_JAR" >&2
  exit 1
fi

MANIFEST="$ROOT_DIR/app/src/main/AndroidManifest.xml"
VERSION_CODE=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$MANIFEST" | head -n1)
VERSION_NAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$MANIFEST" | head -n1)
VERSION_CODE="${VERSION_CODE:-400}"
VERSION_NAME="${VERSION_NAME:-2.0.0}"

BUILD_DIR="$ROOT_DIR/build"
RES_ZIP="$BUILD_DIR/intermediates/res.zip"
GEN_DIR="$BUILD_DIR/generated/r"
CLASSES_DIR="$BUILD_DIR/intermediates/classes"
DEX_DIR="$BUILD_DIR/intermediates/dex"
APK_OUT_DIR="$BUILD_DIR/outputs/apk"
UNSIGNED_APK="$BUILD_DIR/intermediates/unsigned.apk"
ALIGNED_APK="$BUILD_DIR/intermediates/aligned.apk"
FINAL_APK="$APK_OUT_DIR/HomeMate-Gateway-debug.apk"
RELEASE_SLIM_APK="$APK_OUT_DIR/HomeMate-Gateway-v${VERSION_NAME}-slim.apk"
RELEASE_FULL_APK="$APK_OUT_DIR/HomeMate-Gateway-v${VERSION_NAME}-full-arm64.apk"
RELEASE_UNIVERSAL_APK="$APK_OUT_DIR/HomeMate-Gateway-v${VERSION_NAME}-universal.apk"

# Persistent signing keystore outside build/ so updates always keep matching signing certificate
KEYSTORE="${HM_ANDROID_KEYSTORE:-$HOME/.home-mate/android-release.keystore}"
mkdir -p "$(dirname "$KEYSTORE")"
if [[ ! -f "$KEYSTORE" ]]; then
  if [[ -f "$BUILD_DIR/debug.keystore" ]]; then
    cp "$BUILD_DIR/debug.keystore" "$KEYSTORE"
  else
    keytool -genkeypair \
      -alias androiddebugkey \
      -keypass android \
      -keystore "$KEYSTORE" \
      -storepass android \
      -dname "CN=Home Mate Android,O=HomeMate,C=CN" \
      -keyalg RSA \
      -keysize 2048 \
      -validity 10000 >/dev/null 2>&1
  fi
fi

# Prefer 16KB page alignment (-P 16) when supported by zipalign (Android 15+ compatibility)
ZIPALIGN_FLAGS=(-f -p 4)
if "$ZIPALIGN" -h 2>&1 | grep -q -- "-P"; then
  ZIPALIGN_FLAGS=(-f -P 16 4)
fi

rm -rf "$BUILD_DIR/intermediates" "$BUILD_DIR/generated"
mkdir -p "$BUILD_DIR/intermediates" "$GEN_DIR" "$CLASSES_DIR" "$DEX_DIR" "$APK_OUT_DIR"

echo "[1/6] Compiling Android resources with aapt2 (v${VERSION_NAME} / code ${VERSION_CODE})..."
"$AAPT2" compile --dir "$ROOT_DIR/app/src/main/res" -o "$RES_ZIP"

"$AAPT2" link \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" \
  --java "$GEN_DIR" \
  --min-sdk-version 21 \
  --target-sdk-version 34 \
  -o "$UNSIGNED_APK" \
  "$RES_ZIP"

echo "[2/6] Compiling Java sources with javac..."
JAVA_SOURCES=()
while IFS= read -r -d '' file; do
  JAVA_SOURCES+=("$file")
done < <(find "$ROOT_DIR/app/src/main/java" "$GEN_DIR" -name "*.java" -print0)

javac --release 11 \
  -encoding UTF-8 \
  -cp "$ANDROID_JAR" \
  -d "$CLASSES_DIR" \
  "${JAVA_SOURCES[@]}"

echo "[3/6] Converting JVM bytecode to Dalvik DEX with d8..."
CLASS_FILES=()
while IFS= read -r -d '' file; do
  CLASS_FILES+=("$file")
done < <(find "$CLASSES_DIR" -name "*.class" -print0)

"$D8" --min-api 21 \
  --lib "$ANDROID_JAR" \
  --output "$DEX_DIR" \
  "${CLASS_FILES[@]}"

echo "[4/6] Packaging classes.dex & signing slim APK..."
(cd "$DEX_DIR" && zip -q "$UNSIGNED_APK" classes.dex)
"$ZIPALIGN" "${ZIPALIGN_FLAGS[@]}" "$UNSIGNED_APK" "$ALIGNED_APK"

"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$FINAL_APK" \
  "$ALIGNED_APK"

"$APKSIGNER" verify --verbose "$FINAL_APK" >/dev/null
cp "$FINAL_APK" "$RELEASE_SLIM_APK"
SLIM_SIZE=$(stat -f%z "$RELEASE_SLIM_APK" 2>/dev/null || stat -c%s "$RELEASE_SLIM_APK")
echo "  -> Built slim APK: $RELEASE_SLIM_APK (${SLIM_SIZE} bytes)"

# [5/6] Package Official Android NDK JNI Sherpa-ONNX (libsherpa-onnx-jni.so) + WenetSpeech Zipformer int8 model
KWS_BUNDLE_DIR="${HM_KWS_BUNDLE_DIR:-$HOME/.home-mate/kws-build/bundle}"
SHERPA_AAR_DIR="${HM_SHERPA_AAR_DIR:-$HOME/.home-mate/android-deps/sherpa-aar}"

if [[ -d "$KWS_BUNDLE_DIR/model" && -f "$SHERPA_AAR_DIR/jni/arm64-v8a/libsherpa-onnx-jni.so" ]]; then
  echo "[5/6] Packaging Android NDK JNI Sherpa-ONNX (arm64-v8a) & WenetSpeech KWS model..."
  STAGE_DIR="$BUILD_DIR/intermediates/full_stage"
  rm -rf "$STAGE_DIR"
  mkdir -p "$STAGE_DIR/assets/kws/model" "$STAGE_DIR/lib/arm64-v8a"
  cp "$UNSIGNED_APK" "$BUILD_DIR/intermediates/unsigned-full.apk"

  for mf in tokens.txt \
            encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx \
            decoder-epoch-13-avg-2-chunk-16-left-64.onnx \
            joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx; do
    cp "$KWS_BUNDLE_DIR/model/$mf" "$STAGE_DIR/assets/kws/model/$mf"
  done

  cp "$SHERPA_AAR_DIR/jni/arm64-v8a/libsherpa-onnx-jni.so" "$STAGE_DIR/lib/arm64-v8a/libsherpa-onnx-jni.so"

  (cd "$STAGE_DIR" && zip -q -r "$BUILD_DIR/intermediates/unsigned-full.apk" assets lib)
  "$ZIPALIGN" "${ZIPALIGN_FLAGS[@]}" "$BUILD_DIR/intermediates/unsigned-full.apk" "$BUILD_DIR/intermediates/aligned-full.apk"

  echo "[6/6] Signing arm64-v8a & universal JNI offline KWS APKs..."
  "$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$RELEASE_FULL_APK" \
    "$BUILD_DIR/intermediates/aligned-full.apk"

  "$APKSIGNER" verify --verbose "$RELEASE_FULL_APK" >/dev/null
  FULL_SIZE=$(stat -f%z "$RELEASE_FULL_APK" 2>/dev/null || stat -c%s "$RELEASE_FULL_APK")
  echo "  -> Built arm64-v8a JNI offline KWS APK: $RELEASE_FULL_APK (${FULL_SIZE} bytes)"

  if [[ -f "$SHERPA_AAR_DIR/jni/armeabi-v7a/libsherpa-onnx-jni.so" ]]; then
    mkdir -p "$STAGE_DIR/lib/armeabi-v7a"
    cp "$SHERPA_AAR_DIR/jni/armeabi-v7a/libsherpa-onnx-jni.so" "$STAGE_DIR/lib/armeabi-v7a/libsherpa-onnx-jni.so"
    cp "$UNSIGNED_APK" "$BUILD_DIR/intermediates/unsigned-univ.apk"
    (cd "$STAGE_DIR" && zip -q -r "$BUILD_DIR/intermediates/unsigned-univ.apk" assets lib)
    "$ZIPALIGN" "${ZIPALIGN_FLAGS[@]}" "$BUILD_DIR/intermediates/unsigned-univ.apk" "$BUILD_DIR/intermediates/aligned-univ.apk"
    "$APKSIGNER" sign \
      --ks "$KEYSTORE" \
      --ks-key-alias androiddebugkey \
      --ks-pass pass:android \
      --key-pass pass:android \
      --out "$RELEASE_UNIVERSAL_APK" \
      "$BUILD_DIR/intermediates/aligned-univ.apk"
    "$APKSIGNER" verify --verbose "$RELEASE_UNIVERSAL_APK" >/dev/null
    cp -f "$RELEASE_UNIVERSAL_APK" "$APK_OUT_DIR/hm.apk"
    UNIV_SIZE=$(stat -f%z "$RELEASE_UNIVERSAL_APK" 2>/dev/null || stat -c%s "$RELEASE_UNIVERSAL_APK")
    echo "  -> Built universal (arm64+armv7) JNI offline KWS APK: $RELEASE_UNIVERSAL_APK (${UNIV_SIZE} bytes)"
  else
    cp -f "$RELEASE_FULL_APK" "$APK_OUT_DIR/hm.apk"
  fi

  cat > "$APK_OUT_DIR/version.json" <<EOF
{
  "version_code": ${VERSION_CODE},
  "version_name": "${VERSION_NAME}",
  "apk_url": "hm.apk",
  "notes": "v${VERSION_NAME}: Persistent core_id LAN/Tailscale deduplication & auto-routing, Option 1A LAN auth exemption & :8932 Hello auth_token support, idle-mic uplink gating with local preroll buffer (zero idle ASR noise), zero raw :8932 port probing, and zero hardcoded personal IPs."
}
EOF
else
  echo "[5/6] Local KWS model or Sherpa-ONNX Android AAR not found; skipped JNI offline bundle."
fi

echo "SUCCESS: v${VERSION_NAME} (code ${VERSION_CODE}) APK build complete."
