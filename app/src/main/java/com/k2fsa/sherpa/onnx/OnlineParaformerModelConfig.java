package com.k2fsa.sherpa.onnx;

public final class OnlineParaformerModelConfig {
    private String encoder;
    private String decoder;

    public OnlineParaformerModelConfig() {
        this("", "");
    }

    public OnlineParaformerModelConfig(String encoder, String decoder) {
        this.encoder = encoder != null ? encoder : "";
        this.decoder = decoder != null ? decoder : "";
    }

    public String getEncoder() { return encoder; }
    public String getDecoder() { return decoder; }
}
