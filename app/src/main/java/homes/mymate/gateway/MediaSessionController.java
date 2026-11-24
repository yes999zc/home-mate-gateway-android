package homes.mymate.gateway;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;

/**
 * Native Android {@link MediaSession} bridge for Home Mate Gateway (v2.0).
 *
 * v2.0 hardening:
 * - Thread-safe synchronized lifecycle with {@code released} guard (no post-release crashes).
 * - Stays inactive ({@code setActive(false)} & {@code STATE_NONE}) while idle so it never hijacks
 *   Bluetooth headphone Play/Pause buttons from Spotify, QQ Music, or NetEase Cloud Music.
 * - Updates metadata whenever EITHER title OR subtitle changes.
 */
public final class MediaSessionController {

    public interface MediaActionListener {
        void onMediaPlayPause(boolean currentlyPlaying);
        void onMediaNext();
        void onMediaPrevious();
        void onMediaStop();
    }

    private final MediaSession mediaSession;
    private final MediaActionListener actionListener;
    private boolean released = false;
    private boolean active = false;
    private String currentTitle = "";
    private String currentSubtitle = "";

    public MediaSessionController(Context context, final MediaActionListener listener) {
        this.actionListener = listener;
        this.mediaSession = new MediaSession(context.getApplicationContext(), "HomeMateGatewaySession");
        this.mediaSession.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        this.mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (actionListener != null) {
                    actionListener.onMediaPlayPause(false);
                }
            }

            @Override
            public void onPause() {
                if (actionListener != null) {
                    actionListener.onMediaPlayPause(true);
                }
            }

            @Override
            public void onSkipToNext() {
                if (actionListener != null) {
                    actionListener.onMediaNext();
                }
            }

            @Override
            public void onSkipToPrevious() {
                if (actionListener != null) {
                    actionListener.onMediaPrevious();
                }
            }

            @Override
            public void onStop() {
                if (actionListener != null) {
                    actionListener.onMediaStop();
                }
            }
        });
        this.mediaSession.setActive(false);
    }

    public synchronized MediaSession.Token getSessionToken() {
        return released ? null : mediaSession.getSessionToken();
    }

    public synchronized void updatePlaybackState(boolean playing, String title, String subtitle) {
        if (released) {
            return;
        }
        try {
            if (!playing) {
                if (active) {
                    PlaybackState idleState = new PlaybackState.Builder()
                            .setActions(0L)
                            .setState(PlaybackState.STATE_NONE, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0.0f)
                            .build();
                    mediaSession.setPlaybackState(idleState);
                    mediaSession.setActive(false);
                    active = false;
                }
                return;
            }

            if (!active) {
                mediaSession.setActive(true);
                active = true;
            }

            String displayTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : "Home Mate Audio";
            String displaySub = (subtitle != null && !subtitle.trim().isEmpty()) ? subtitle.trim() : "Home Mate";
            if (!displayTitle.equals(this.currentTitle) || !displaySub.equals(this.currentSubtitle)) {
                this.currentTitle = displayTitle;
                this.currentSubtitle = displaySub;
                MediaMetadata meta = new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, displayTitle)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, displaySub)
                        .build();
                mediaSession.setMetadata(meta);
            }

            long actions = PlaybackState.ACTION_PLAY
                    | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_STOP;
            PlaybackState pbState = new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .build();
            mediaSession.setPlaybackState(pbState);
        } catch (Throwable ignored) {
        }
    }

    public synchronized void release() {
        if (released) {
            return;
        }
        released = true;
        active = false;
        try {
            mediaSession.setActive(false);
            mediaSession.release();
        } catch (Throwable ignored) {
        }
    }
}
