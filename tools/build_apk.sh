#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

RELEASE_BUILD=0
VALIDATE_SIGNING_ONLY=0
if [[ "${HM_RELEASE_BUILD:-0}" == "1" || "${HM_RELEASE_BUILD:-}" == "true" ]]; then
  RELEASE_BUILD=1
fi

for arg in "$@"; do
  case "$arg" in
    --release)
      RELEASE_BUILD=1
      ;;
    --validate-signing)
      VALIDATE_SIGNING_ONLY=1
      ;;
    -h|--help)
      cat <<'USAGE'
Usage: ./tools/build_apk.sh [--release] [--validate-signing]

Signing environment variables:
  HM_ANDROID_KEYSTORE           Path to signing keystore (required in --release mode)
  HM_ANDROID_KEY_ALIAS          Key alias inside keystore (required & non-debug in --release mode)
  HM_ANDROID_KEYSTORE_PASS      Keystore password, or env:<VAR> / file:<PATH>
  HM_ANDROID_KEYSTORE_PASS_FILE Path to file containing keystore password
  HM_ANDROID_KEY_PASS           Key password, or env:<VAR> / file:<PATH>
  HM_ANDROID_KEY_PASS_FILE      Path to file containing key password
  HM_RELEASE_BUILD=1            Equivalent to --release

Flags:
  --release           Require explicit production signing credentials; refuse androiddebugkey / android.
  --validate-signing  Validate signing configuration and exit without building APKs.
USAGE
      exit 0
      ;;
    *)
      echo "ERROR: Unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

# Resolve a signing password without ever placing secrets in command-line arguments (safe from ps/history).
# Sets global RESOLVED_PASS_ARG (apksigner spec: env:<VAR> or file:<PATH>) and RESOLVED_PASS_VALUE (in-memory only).
resolve_signing_password() {
  local label="$1"
  local raw_var="$2"
  local file_var="$3"
  local internal_env_var="$4"
  local dev_fallback_arg="${5:-}"
  local dev_fallback_val="${6:-}"

  local pass_file="${!file_var:-}"
  local raw_val="${!raw_var:-}"
  local secret_val=""
  local pass_arg=""

  if [[ -n "$pass_file" ]]; then
    if [[ ! -r "$pass_file" ]]; then
      echo "ERROR: ${file_var} is not readable: $pass_file" >&2
      exit 1
    fi
    IFS= read -r secret_val < "$pass_file" || true
    secret_val="${secret_val%$'\r'}"
    if [[ -z "$secret_val" ]]; then
      echo "ERROR: ${file_var} is empty: $pass_file" >&2
      exit 1
    fi
    pass_arg="file:${pass_file}"
  elif [[ -n "$raw_val" ]]; then
    if [[ "$raw_val" == env:* ]]; then
      local ref_var="${raw_val#env:}"
      if [[ ! "$ref_var" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
        echo "ERROR: Invalid env: variable reference in ${raw_var}" >&2
        exit 1
      fi
      secret_val="${!ref_var:-}"
      if [[ -z "$secret_val" ]]; then
        echo "ERROR: Referenced signing env var ${ref_var} (via ${raw_var}) is empty or unset" >&2
        exit 1
      fi
      export "$ref_var"
      pass_arg="env:${ref_var}"
    elif [[ "$raw_val" == file:* ]]; then
      local ref_file="${raw_val#file:}"
      if [[ ! -r "$ref_file" ]]; then
        echo "ERROR: Referenced signing password file (via ${raw_var}) is not readable: $ref_file" >&2
        exit 1
      fi
      IFS= read -r secret_val < "$ref_file" || true
      secret_val="${secret_val%$'\r'}"
      if [[ -z "$secret_val" ]]; then
        echo "ERROR: Referenced signing password file (via ${raw_var}) is empty: $ref_file" >&2
        exit 1
      fi
      pass_arg="file:${ref_file}"
    elif [[ "$raw_val" == pass:* ]]; then
      secret_val="${raw_val#pass:}"
      if [[ -z "$secret_val" ]]; then
        echo "ERROR: Empty pass: value in ${raw_var}" >&2
        exit 1
      fi
      printf -v "$internal_env_var" '%s' "$secret_val"
      export "$internal_env_var"
      pass_arg="env:${internal_env_var}"
    else
      secret_val="$raw_val"
      export "$raw_var"
      pass_arg="env:${raw_var}"
    fi
  else
    if [[ "$RELEASE_BUILD" -eq 1 ]]; then
      echo "ERROR: --release build requires explicit ${label} password via ${raw_var} or ${file_var}." >&2
      exit 1
    fi
    if [[ -n "$dev_fallback_arg" ]]; then
      pass_arg="$dev_fallback_arg"
      secret_val="$dev_fallback_val"
    else
      secret_val="android"
      printf -v "$internal_env_var" '%s' "$secret_val"
      export "$internal_env_var"
      pass_arg="env:${internal_env_var}"
    fi
  fi

  if [[ "$RELEASE_BUILD" -eq 1 && "$secret_val" == "android" ]]; then
    echo "ERROR: --release build refuses default 'android' ${label} password. Provide a production secret." >&2
    exit 1
  fi

  RESOLVED_PASS_ARG="$pass_arg"
  RESOLVED_PASS_VALUE="$secret_val"
}

if [[ "$RELEASE_BUILD" -eq 1 ]]; then
  KEYSTORE="${HM_ANDROID_KEYSTORE:-}"
  KEY_ALIAS="${HM_ANDROID_KEY_ALIAS:-}"
  if [[ -z "$KEYSTORE" ]]; then
    echo "ERROR: --release build requires explicit HM_ANDROID_KEYSTORE path." >&2
    exit 1
  fi
  if [[ ! -f "$KEYSTORE" ]]; then
    echo "ERROR: --release keystore file does not exist: $KEYSTORE" >&2
    exit 1
  fi
  if [[ -z "$KEY_ALIAS" ]]; then
    echo "ERROR: --release build requires explicit HM_ANDROID_KEY_ALIAS." >&2
    exit 1
  fi
  KEY_ALIAS_LOWER="$(printf '%s' "$KEY_ALIAS" | tr '[:upper:]' '[:lower:]')"
  if [[ "$KEY_ALIAS_LOWER" == "androiddebugkey" ]]; then
    echo "ERROR: --release build refuses debug alias 'androiddebugkey'. Provide a production key alias." >&2
    exit 1
  fi
  resolve_signing_password "keystore" "HM_ANDROID_KEYSTORE_PASS" "HM_ANDROID_KEYSTORE_PASS_FILE" "_HM_KS_PASS_SECRET"
  KS_PASS_ARG="$RESOLVED_PASS_ARG"
  KS_PASS_VAL="$RESOLVED_PASS_VALUE"

  resolve_signing_password "key" "HM_ANDROID_KEY_PASS" "HM_ANDROID_KEY_PASS_FILE" "_HM_KEY_PASS_SECRET"
  KEY_PASS_ARG="$RESOLVED_PASS_ARG"
  KEY_PASS_VAL="$RESOLVED_PASS_VALUE"
else
  # Local dev/LAN build: preserve backward-compatible fallback to persistent local keystore
  # so incremental upgrades on developer test devices retain matching signing certificate.
  KEYSTORE="${HM_ANDROID_KEYSTORE:-$HOME/.home-mate/android-release.keystore}"
  KEY_ALIAS="${HM_ANDROID_KEY_ALIAS:-androiddebugkey}"

  resolve_signing_password "keystore" "HM_ANDROID_KEYSTORE_PASS" "HM_ANDROID_KEYSTORE_PASS_FILE" "_HM_DEV_KS_PASS"
  KS_PASS_ARG="$RESOLVED_PASS_ARG"
  KS_PASS_VAL="$RESOLVED_PASS_VALUE"

  if [[ -n "${HM_ANDROID_KEYSTORE_PASS:-}" || -n "${HM_ANDROID_KEYSTORE_PASS_FILE:-}" ]]; then
    resolve_signing_password "key" "HM_ANDROID_KEY_PASS" "HM_ANDROID_KEY_PASS_FILE" "_HM_DEV_KEY_PASS" "$KS_PASS_ARG" "$KS_PASS_VAL"
  else
    resolve_signing_password "key" "HM_ANDROID_KEY_PASS" "HM_ANDROID_KEY_PASS_FILE" "_HM_DEV_KEY_PASS"
  fi
  KEY_PASS_ARG="$RESOLVED_PASS_ARG"
  KEY_PASS_VAL="$RESOLVED_PASS_VALUE"
fi

if [[ "$VALIDATE_SIGNING_ONLY" -eq 1 ]]; then
  echo "OK: Signing configuration valid (release=${RELEASE_BUILD}, alias=${KEY_ALIAS}, ks_pass=${KS_PASS_ARG}, key_pass=${KEY_PASS_ARG})."
  exit 0
fi

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

# Dev-only auto-creation of persistent local keystore (release mode already verified existing file above)
if [[ "$RELEASE_BUILD" -eq 0 ]]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  if [[ ! -f "$KEYSTORE" ]]; then
    if [[ -f "$BUILD_DIR/debug.keystore" && "$KEY_ALIAS" == "androiddebugkey" ]]; then
      cp "$BUILD_DIR/debug.keystore" "$KEYSTORE"
    else
      export _HM_KEYTOOL_SP="$KS_PASS_VAL"
      export _HM_KEYTOOL_KP="$KEY_PASS_VAL"
      keytool -genkeypair \
        -alias "$KEY_ALIAS" \
        -keystore "$KEYSTORE" \
        -storepass:env _HM_KEYTOOL_SP \
        -keypass:env _HM_KEYTOOL_KP \
        -dname "CN=Home Mate Android,O=HomeMate,C=CN" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 >/dev/null 2>&1
      unset _HM_KEYTOOL_SP _HM_KEYTOOL_KP
    fi
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
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "$KS_PASS_ARG" \
  --key-pass "$KEY_PASS_ARG" \
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
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "$KS_PASS_ARG" \
    --key-pass "$KEY_PASS_ARG" \
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
      --ks-key-alias "$KEY_ALIAS" \
      --ks-pass "$KS_PASS_ARG" \
      --key-pass "$KEY_PASS_ARG" \
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
  "notes": "v${VERSION_NAME}: Zero-drift fractional-phase AEC reference resampler (fixing mid-music KWS AEC phase drift across partial writes at 22.05/24/44.1/48kHz), default 退下 KWS retreat word, and northboundKwsSynced reload-race fix with exact loaded_hash kws_report."
}
EOF
else
  echo "[5/6] Local KWS model or Sherpa-ONNX Android AAR not found; skipped JNI offline bundle."
fi

echo "SUCCESS: v${VERSION_NAME} (code ${VERSION_CODE}) APK build complete."
