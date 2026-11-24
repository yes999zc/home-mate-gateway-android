package com.k2fsa.sherpa.onnx;

public final class OnlineModelConfig {
    private OnlineTransducerModelConfig transducer;
    private OnlineParaformerModelConfig paraformer;
    private OnlineZipformer2CtcModelConfig zipformer2Ctc;
    private OnlineNeMoCtcModelConfig neMoCtc;
    private OnlineToneCtcModelConfig toneCtc;
    private String tokens;
    private int numThreads;
    private boolean debug;
    private String provider;
    private String modelType;
    private String modelingUnit;
    private String bpeVocab;

    public OnlineModelConfig(
            OnlineTransducerModelConfig transducer,
            String tokens,
            int numThreads,
            boolean debug,
            String provider) {
        this.transducer = transducer != null ? transducer : new OnlineTransducerModelConfig();
        this.paraformer = new OnlineParaformerModelConfig();
        this.zipformer2Ctc = new OnlineZipformer2CtcModelConfig();
        this.neMoCtc = new OnlineNeMoCtcModelConfig();
        this.toneCtc = new OnlineToneCtcModelConfig();
        this.tokens = tokens != null ? tokens : "";
        this.numThreads = Math.max(1, numThreads);
        this.debug = debug;
        this.provider = provider != null ? provider : "cpu";
        this.modelType = "";
        this.modelingUnit = "";
        this.bpeVocab = "";
    }

    public void setModelType(String modelType) {
        this.modelType = modelType != null ? modelType : "";
    }
}
