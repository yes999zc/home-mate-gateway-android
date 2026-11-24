package com.k2fsa.sherpa.onnx;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure-Java thread-safe JNI binding for Sherpa-ONNX {@code libsherpa-onnx-jni.so}.
 * Uses AtomicLong.getAndSet(0L) to guarantee at-most-once native destruction.
 */
public final class KeywordSpotter {
    private static volatile boolean nativeLoaded = false;
    private static volatile String loadError = null;

    static {
        try {
            System.loadLibrary("sherpa-onnx-jni");
            nativeLoaded = true;
        } catch (Throwable t) {
            nativeLoaded = false;
            loadError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    public static boolean isNativeAvailable() {
        return nativeLoaded;
    }

    public static String getLoadError() {
        return loadError;
    }

    private final KeywordSpotterConfig config;
    private final AtomicLong ptr = new AtomicLong(0L);

    public KeywordSpotter(KeywordSpotterConfig config) {
        this.config = config;
        if (!nativeLoaded) {
            throw new IllegalStateException("libsherpa-onnx-jni.so not loaded: " + loadError);
        }
        long handle = newFromFile(config);
        if (handle == 0L) {
            throw new IllegalStateException("Sherpa-ONNX KeywordSpotter newFromFile returned null pointer");
        }
        this.ptr.set(handle);
    }

    public KeywordSpotterConfig getConfig() {
        return config;
    }

    public OnlineStream createStream(String keywords) {
        long p = ptr.get();
        if (p == 0L) {
            return new OnlineStream(0L);
        }
        long streamPtr = createStream(p, keywords != null ? keywords : "");
        return new OnlineStream(streamPtr);
    }

    public boolean isReady(OnlineStream stream) {
        long p = ptr.get();
        long sp = stream != null ? stream.getPtr() : 0L;
        return p != 0L && sp != 0L && isReady(p, sp);
    }

    public void decode(OnlineStream stream) {
        long p = ptr.get();
        long sp = stream != null ? stream.getPtr() : 0L;
        if (p != 0L && sp != 0L) {
            decode(p, sp);
        }
    }

    public void reset(OnlineStream stream) {
        long p = ptr.get();
        long sp = stream != null ? stream.getPtr() : 0L;
        if (p != 0L && sp != 0L) {
            reset(p, sp);
        }
    }

    public KeywordSpotterResult getResult(OnlineStream stream) {
        long p = ptr.get();
        long sp = stream != null ? stream.getPtr() : 0L;
        if (p == 0L || sp == 0L) {
            return new KeywordSpotterResult("", new String[0], new float[0]);
        }
        return getResult(p, sp);
    }

    public void release() {
        long p = ptr.getAndSet(0L);
        if (p != 0L) {
            delete(p);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        release();
        super.finalize();
    }

    private native void delete(long ptr);
    private native long newFromFile(KeywordSpotterConfig config);
    private native long createStream(long ptr, String keywords);
    private native boolean isReady(long ptr, long streamPtr);
    private native void decode(long ptr, long streamPtr);
    private native void reset(long ptr, long streamPtr);
    private native KeywordSpotterResult getResult(long ptr, long streamPtr);
}
