package com.k2fsa.sherpa.onnx;

public final class FeatureConfig {
    private int sampleRate;
    private int featureDim;
    private float dither;

    public FeatureConfig() {
        this(16000, 80, 0.0f);
    }

    public FeatureConfig(int sampleRate, int featureDim, float dither) {
        this.sampleRate = sampleRate;
        this.featureDim = featureDim;
        this.dither = dither;
    }

    public int getSampleRate() { return sampleRate; }
    public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }
    public int getFeatureDim() { return featureDim; }
    public void setFeatureDim(int featureDim) { this.featureDim = featureDim; }
    public float getDither() { return dither; }
    public void setDither(float dither) { this.dither = dither; }
}
