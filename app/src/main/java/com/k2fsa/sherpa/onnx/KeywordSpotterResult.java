package com.k2fsa.sherpa.onnx;

public final class KeywordSpotterResult {
    private final String keyword;
    private final String[] tokens;
    private final float[] timestamps;

    public KeywordSpotterResult(String keyword, String[] tokens, float[] timestamps) {
        this.keyword = keyword != null ? keyword : "";
        this.tokens = tokens != null ? tokens : new String[0];
        this.timestamps = timestamps != null ? timestamps : new float[0];
    }

    public String getKeyword() { return keyword; }
    public String[] getTokens() { return tokens; }
    public float[] getTimestamps() { return timestamps; }
}
