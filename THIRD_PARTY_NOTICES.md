# Third-Party Licenses & Notices

Home Mate Android Universal Client & Voice Gateway incorporates or interoperates with the following open-source software components and model assets. We gratefully acknowledge their authors and maintainers.

| Component | License | Usage & Source |
| :--- | :--- | :--- |
| `sherpa-onnx` | `Apache-2.0` | In-process offline Zipformer int8 KWS (`arm64-v8a` & `armeabi-v7a` JNI) — https://github.com/k2-fsa/sherpa-onnx |
| `onnxruntime` | `MIT` | ONNX Runtime shared libraries bundled for offline KWS inference — https://github.com/microsoft/onnxruntime |
| Android SDK (Java) | `Apache-2.0` | Platform APIs (`AudioRecord`, `AudioTrack`, `WebView`, `ConnectivityManager`) |
| `junit` (test-only) | `EPL-1.0` | Local JVM unit & regression test harness (`./tools/test_jvm.sh`) |
