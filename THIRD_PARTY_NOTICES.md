# Third-Party Licenses & Notices

Home Mate Android Universal Client & Voice Gateway incorporates or interoperates with the following open-source software components and model assets. We gratefully acknowledge their authors and maintainers.

| Component | License | Usage & Source |
| :--- | :--- | :--- |
| `sherpa-onnx` | `Apache-2.0` | In-process offline Zipformer int8 KWS (`arm64-v8a` & `armeabi-v7a` JNI) — https://github.com/k2-fsa/sherpa-onnx |
| `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20` | `Apache-2.0` | Bundled offline bilingual Zipformer int8 KWS ONNX model (`assets/kws/model/`) — https://github.com/k2-fsa/sherpa-onnx |
| `onnxruntime` | `MIT` | ONNX Runtime shared/static library bundled inside `libsherpa-onnx-jni.so` — https://onnxruntime.ai/ |
| `eigen` | `MPL-2.0` | Linear algebra library statically linked via ONNX Runtime / `sherpa-onnx` (unmodified upstream source at https://gitlab.com/libeigen/eigen) |
| `kaldi-native-fbank` / `kaldi-decoder` / `openfst` | `Apache-2.0` | Acoustic feature extraction & WFST decoding statically linked inside `libsherpa-onnx-jni.so` |
| `protobuf` | `BSD-3-Clause` | Model serialization statically linked via ONNX Runtime — https://github.com/protocolbuffers/protobuf |
| `abseil-cpp` | `Apache-2.0` | C++ utility library statically linked via ONNX Runtime — https://github.com/abseil/abseil-cpp |
| Android SDK (Java) | `Apache-2.0` | Platform APIs (`AudioRecord`, `AudioTrack`, `WebView`, `ConnectivityManager`) |
