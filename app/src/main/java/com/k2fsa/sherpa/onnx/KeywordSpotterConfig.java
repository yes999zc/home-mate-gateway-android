package com.k2fsa.sherpa.onnx;

public final class KeywordSpotterConfig {
    private FeatureConfig featConfig;
    private OnlineModelConfig modelConfig;
    private int maxActivePaths;
    private String keywordsFile;
    private float keywordsScore;
    private float keywordsThreshold;
    private int numTrailingBlanks;

    public KeywordSpotterConfig(
            FeatureConfig featConfig,
            OnlineModelConfig modelConfig,
            int maxActivePaths,
            String keywordsFile,
            float keywordsScore,
            float keywordsThreshold,
            int numTrailingBlanks) {
        this.featConfig = featConfig != null ? featConfig : new FeatureConfig();
        this.modelConfig = modelConfig;
        this.maxActivePaths = maxActivePaths > 0 ? maxActivePaths : 4;
        this.keywordsFile = keywordsFile != null ? keywordsFile : "";
        this.keywordsScore = keywordsScore;
        this.keywordsThreshold = keywordsThreshold;
        this.numTrailingBlanks = Math.max(0, numTrailingBlanks);
    }

    public FeatureConfig getFeatConfig() { return featConfig; }
    public OnlineModelConfig getModelConfig() { return modelConfig; }
    public int getMaxActivePaths() { return maxActivePaths; }
    public String getKeywordsFile() { return keywordsFile; }
    public float getKeywordsScore() { return keywordsScore; }
    public float getKeywordsThreshold() { return keywordsThreshold; }
    public int getNumTrailingBlanks() { return numTrailingBlanks; }
}
