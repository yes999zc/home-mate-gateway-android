package com.k2fsa.sherpa.onnx;

public final class QnnConfig {
    private String backendLib;
    private String contextBinary;
    private String systemLib;
    private boolean htpPerfMode;

    public QnnConfig() {
        this("", "", "", false);
    }

    public QnnConfig(String backendLib, String contextBinary, String systemLib, boolean htpPerfMode) {
        this.backendLib = backendLib != null ? backendLib : "";
        this.contextBinary = contextBinary != null ? contextBinary : "";
        this.systemLib = systemLib != null ? systemLib : "";
        this.htpPerfMode = htpPerfMode;
    }

    public String getBackendLib() { return backendLib; }
    public String getContextBinary() { return contextBinary; }
    public String getSystemLib() { return systemLib; }
    public boolean getHtpPerfMode() { return htpPerfMode; }
}
