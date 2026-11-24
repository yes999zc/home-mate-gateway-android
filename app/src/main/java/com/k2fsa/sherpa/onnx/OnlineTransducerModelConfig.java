package com.k2fsa.sherpa.onnx;

public final class OnlineTransducerModelConfig {
    private String encoder;
    private String decoder;
    private String joiner;
    private QnnConfig qnnConfig;

    public OnlineTransducerModelConfig() {
        this("", "", "", new QnnConfig());
    }

    public OnlineTransducerModelConfig(String encoder, String decoder, String joiner) {
        this(encoder, decoder, joiner, new QnnConfig());
    }

    public OnlineTransducerModelConfig(String encoder, String decoder, String joiner, QnnConfig qnnConfig) {
        this.encoder = encoder != null ? encoder : "";
        this.decoder = decoder != null ? decoder : "";
        this.joiner = joiner != null ? joiner : "";
        this.qnnConfig = qnnConfig != null ? qnnConfig : new QnnConfig();
    }

    public String getEncoder() { return encoder; }
    public String getDecoder() { return decoder; }
    public String getJoiner() { return joiner; }
    public QnnConfig getQnnConfig() { return qnnConfig; }
}
