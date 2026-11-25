package homes.mymate.gateway.protocol;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Race-free, bounded 2-tier outbound queue for {@code hm-core} Northbound frames.
 *
 * <p>Uses a single intrinsic monitor around both {@code controlQueue} and {@code audioQueue}
 * so concurrent producers (capture thread, preroll flush, UI actions, KWS) and the single
 * socket writer thread can never leak wakeup permits or suffer underflow stalls.
 * Control frames always drain ahead of bulk audio frames.
 */
public final class BoundedTwoTierQueue {

    private final int controlCap;
    private final int audioCap;
    private final Deque<String> controlQueue;
    private final Deque<String> audioQueue;
    private boolean closed = false;
    private long droppedAudio = 0L;
    private long droppedControl = 0L;

    public BoundedTwoTierQueue(int controlCap, int audioCap) {
        this.controlCap = Math.max(1, controlCap);
        this.audioCap = Math.max(1, audioCap);
        this.controlQueue = new ArrayDeque<String>(this.controlCap);
        this.audioQueue = new ArrayDeque<String>(this.audioCap);
    }

    public synchronized boolean enqueue(String jsonLine, boolean isAudioBulk) {
        if (closed || jsonLine == null) {
            return false;
        }
        if (isAudioBulk) {
            while (audioQueue.size() >= audioCap) {
                audioQueue.pollFirst();
                droppedAudio++;
            }
            audioQueue.addLast(jsonLine);
        } else {
            while (controlQueue.size() >= controlCap) {
                controlQueue.pollFirst();
                droppedControl++;
            }
            controlQueue.addLast(jsonLine);
        }
        notify();
        return true;
    }

    /**
     * Blocks until a frame is available (prioritizing control over audio), the timeout elapses,
     * or the queue is closed.
     */
    public synchronized String takeNext(long timeoutMs) throws InterruptedException {
        long deadline = timeoutMs > 0L ? (System.currentTimeMillis() + timeoutMs) : 0L;
        while (!closed && controlQueue.isEmpty() && audioQueue.isEmpty()) {
            if (timeoutMs <= 0L) {
                return null;
            }
            long rem = deadline - System.currentTimeMillis();
            if (rem <= 0L) {
                return null;
            }
            wait(rem);
        }
        if (!controlQueue.isEmpty()) {
            return controlQueue.pollFirst();
        }
        if (!audioQueue.isEmpty()) {
            return audioQueue.pollFirst();
        }
        return null;
    }

    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized int size() {
        return controlQueue.size() + audioQueue.size();
    }

    public synchronized long getDroppedAudio() {
        return droppedAudio;
    }

    public synchronized long getDroppedControl() {
        return droppedControl;
    }
}
