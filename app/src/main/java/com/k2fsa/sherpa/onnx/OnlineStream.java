package com.k2fsa.sherpa.onnx;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe JNI wrapper for Sherpa-ONNX OnlineStream.
 * Uses AtomicLong.getAndSet(0L) so explicit release() and GC finalize() can NEVER double-free.
 */
public final class OnlineStream {
    private final AtomicLong ptr;

    public OnlineStream(long ptr) {
        this.ptr = new AtomicLong(ptr);
    }

    public long getPtr() {
        return ptr.get();
    }

    public void acceptWaveform(float[] samples, int sampleRate) {
        long p = ptr.get();
        if (p != 0L && samples != null && samples.length > 0) {
            acceptWaveform(p, samples, sampleRate);
        }
    }

    public void inputFinished() {
        long p = ptr.get();
        if (p != 0L) {
            inputFinished(p);
        }
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

    private native void acceptWaveform(long ptr, float[] samples, int sampleRate);
    private native void inputFinished(long ptr);
    private native void setOption(long ptr, String key, String value);
    private native String getOption(long ptr, String key);
    private native void delete(long ptr);
}
