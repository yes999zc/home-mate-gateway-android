package homes.mymate.gateway;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Authored geometric 2dp-stroke vector Drawable icon set for Home Mate Android UI (v2.0).
 * Zero allocations inside {@code draw()} via pre-allocated {@link RectF} and {@link Path}.
 */
public final class Icons {

    public enum Name {
        MIC, MIC_OFF, STOP, MUSIC, PLAY, PAUSE, SKIP_NEXT, SKIP_PREV,
        ROOMS, CONSOLE, SETTINGS, CLOCK, SEND, VOLUME, REFRESH, WIFI, CHECK, EXTERNAL, TRASH, HISTORY,
        CHEVRON_DOWN, CHEVRON_UP, CHEVRON_RIGHT, KEY, CLOSE
    }

    private Icons() {
    }

    public static Drawable of(Name name, int color, int sizePx) {
        return new VectorIconDrawable(name, color, sizePx);
    }

    private static final class VectorIconDrawable extends Drawable {
        private final Name name;
        private final int sizePx;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tmpRect = new RectF();
        private final Path tmpPath = new Path();

        VectorIconDrawable(Name name, int color, int sizePx) {
            this.name = name;
            this.sizePx = sizePx;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(color);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(color);
        }

        @Override
        public int getIntrinsicWidth() { return sizePx; }

        @Override
        public int getIntrinsicHeight() { return sizePx; }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            if (s <= 0f) {
                s = sizePx;
            }
            float ox = b.left + (b.width() - s) * 0.5f;
            float oy = b.top + (b.height() - s) * 0.5f;
            stroke.setStrokeWidth(Math.max(1.8f, s * 0.085f));
            c.save();
            c.translate(ox, oy);
            switch (name) {
                case MIC:
                    drawMic(c, s, false);
                    break;
                case MIC_OFF:
                    drawMic(c, s, true);
                    break;
                case STOP:
                    tmpRect.set(s * 0.24f, s * 0.24f, s * 0.76f, s * 0.76f);
                    c.drawRoundRect(tmpRect, s * 0.10f, s * 0.10f, fill);
                    break;
                case MUSIC:
                    drawMusic(c, s);
                    break;
                case PLAY:
                    drawPlay(c, s);
                    break;
                case PAUSE:
                    tmpRect.set(s * 0.26f, s * 0.22f, s * 0.42f, s * 0.78f);
                    c.drawRoundRect(tmpRect, s * 0.05f, s * 0.05f, fill);
                    tmpRect.set(s * 0.58f, s * 0.22f, s * 0.74f, s * 0.78f);
                    c.drawRoundRect(tmpRect, s * 0.05f, s * 0.05f, fill);
                    break;
                case SKIP_NEXT:
                    drawSkip(c, s, true);
                    break;
                case SKIP_PREV:
                    drawSkip(c, s, false);
                    break;
                case ROOMS:
                    drawRooms(c, s);
                    break;
                case CONSOLE:
                    tmpRect.set(s * 0.14f, s * 0.20f, s * 0.86f, s * 0.70f);
                    c.drawRoundRect(tmpRect, s * 0.09f, s * 0.09f, stroke);
                    c.drawLine(s * 0.36f, s * 0.82f, s * 0.64f, s * 0.82f, stroke);
                    c.drawLine(s * 0.50f, s * 0.70f, s * 0.50f, s * 0.82f, stroke);
                    break;
                case SETTINGS:
                    drawSliders(c, s);
                    break;
                case CLOCK:
                    c.drawCircle(s * 0.50f, s * 0.50f, s * 0.36f, stroke);
                    c.drawLine(s * 0.50f, s * 0.30f, s * 0.50f, s * 0.50f, stroke);
                    c.drawLine(s * 0.50f, s * 0.50f, s * 0.66f, s * 0.58f, stroke);
                    break;
                case SEND:
                    drawSend(c, s);
                    break;
                case VOLUME:
                    drawVolume(c, s);
                    break;
                case REFRESH:
                    tmpRect.set(s * 0.20f, s * 0.20f, s * 0.80f, s * 0.80f);
                    c.drawArc(tmpRect, -60f, 290f, false, stroke);
                    c.drawLine(s * 0.64f, s * 0.20f, s * 0.80f, s * 0.24f, stroke);
                    c.drawLine(s * 0.76f, s * 0.38f, s * 0.80f, s * 0.24f, stroke);
                    break;
                case WIFI:
                    tmpRect.set(s * 0.14f, s * 0.26f, s * 0.86f, s * 0.98f);
                    c.drawArc(tmpRect, -135f, 90f, false, stroke);
                    tmpRect.set(s * 0.28f, s * 0.42f, s * 0.72f, s * 0.86f);
                    c.drawArc(tmpRect, -135f, 90f, false, stroke);
                    c.drawCircle(s * 0.50f, s * 0.76f, s * 0.06f, fill);
                    break;
                case CHECK:
                    tmpPath.reset();
                    tmpPath.moveTo(s * 0.22f, s * 0.52f);
                    tmpPath.lineTo(s * 0.42f, s * 0.72f);
                    tmpPath.lineTo(s * 0.78f, s * 0.30f);
                    c.drawPath(tmpPath, stroke);
                    break;
                case EXTERNAL:
                    tmpRect.set(s * 0.18f, s * 0.32f, s * 0.68f, s * 0.82f);
                    c.drawRoundRect(tmpRect, s * 0.08f, s * 0.08f, stroke);
                    c.drawLine(s * 0.48f, s * 0.52f, s * 0.82f, s * 0.18f, stroke);
                    c.drawLine(s * 0.58f, s * 0.18f, s * 0.82f, s * 0.18f, stroke);
                    c.drawLine(s * 0.82f, s * 0.18f, s * 0.82f, s * 0.42f, stroke);
                    break;
                case TRASH:
                    c.drawLine(s * 0.22f, s * 0.28f, s * 0.78f, s * 0.28f, stroke);
                    c.drawLine(s * 0.40f, s * 0.20f, s * 0.60f, s * 0.20f, stroke);
                    tmpRect.set(s * 0.28f, s * 0.28f, s * 0.72f, s * 0.82f);
                    c.drawRoundRect(tmpRect, s * 0.07f, s * 0.07f, stroke);
                    c.drawLine(s * 0.43f, s * 0.42f, s * 0.43f, s * 0.68f, stroke);
                    c.drawLine(s * 0.57f, s * 0.42f, s * 0.57f, s * 0.68f, stroke);
                    break;
                case HISTORY:
                    tmpRect.set(s * 0.16f, s * 0.20f, s * 0.84f, s * 0.72f);
                    c.drawRoundRect(tmpRect, s * 0.12f, s * 0.12f, stroke);
                    c.drawLine(s * 0.30f, s * 0.40f, s * 0.70f, s * 0.40f, stroke);
                    c.drawLine(s * 0.30f, s * 0.54f, s * 0.56f, s * 0.54f, stroke);
                    c.drawLine(s * 0.32f, s * 0.72f, s * 0.22f, s * 0.84f, stroke);
                    break;
                case CHEVRON_DOWN:
                    tmpPath.reset();
                    tmpPath.moveTo(s * 0.24f, s * 0.38f);
                    tmpPath.lineTo(s * 0.50f, s * 0.64f);
                    tmpPath.lineTo(s * 0.76f, s * 0.38f);
                    c.drawPath(tmpPath, stroke);
                    break;
                case CHEVRON_UP:
                    tmpPath.reset();
                    tmpPath.moveTo(s * 0.24f, s * 0.62f);
                    tmpPath.lineTo(s * 0.50f, s * 0.36f);
                    tmpPath.lineTo(s * 0.76f, s * 0.62f);
                    c.drawPath(tmpPath, stroke);
                    break;
                case CHEVRON_RIGHT:
                    tmpPath.reset();
                    tmpPath.moveTo(s * 0.38f, s * 0.24f);
                    tmpPath.lineTo(s * 0.64f, s * 0.50f);
                    tmpPath.lineTo(s * 0.38f, s * 0.76f);
                    c.drawPath(tmpPath, stroke);
                    break;
                case KEY:
                    c.drawCircle(s * 0.36f, s * 0.50f, s * 0.16f, stroke);
                    c.drawLine(s * 0.52f, s * 0.50f, s * 0.84f, s * 0.50f, stroke);
                    c.drawLine(s * 0.72f, s * 0.50f, s * 0.72f, s * 0.64f, stroke);
                    c.drawLine(s * 0.82f, s * 0.50f, s * 0.82f, s * 0.62f, stroke);
                    break;
                case CLOSE:
                    c.drawLine(s * 0.26f, s * 0.26f, s * 0.74f, s * 0.74f, stroke);
                    c.drawLine(s * 0.74f, s * 0.26f, s * 0.26f, s * 0.74f, stroke);
                    break;
            }
            c.restore();
        }

        private void drawMic(Canvas c, float s, boolean muted) {
            tmpRect.set(s * 0.38f, s * 0.16f, s * 0.62f, s * 0.56f);
            c.drawRoundRect(tmpRect, s * 0.12f, s * 0.12f, stroke);
            tmpRect.set(s * 0.26f, s * 0.28f, s * 0.74f, s * 0.68f);
            c.drawArc(tmpRect, 0f, 180f, false, stroke);
            c.drawLine(s * 0.50f, s * 0.68f, s * 0.50f, s * 0.84f, stroke);
            c.drawLine(s * 0.36f, s * 0.84f, s * 0.64f, s * 0.84f, stroke);
            if (muted) {
                c.drawLine(s * 0.20f, s * 0.20f, s * 0.80f, s * 0.80f, stroke);
            }
        }

        private void drawMusic(Canvas c, float s) {
            c.drawCircle(s * 0.34f, s * 0.74f, s * 0.10f, fill);
            c.drawCircle(s * 0.72f, s * 0.66f, s * 0.10f, fill);
            c.drawLine(s * 0.43f, s * 0.74f, s * 0.43f, s * 0.28f, stroke);
            c.drawLine(s * 0.81f, s * 0.66f, s * 0.81f, s * 0.20f, stroke);
            c.drawLine(s * 0.43f, s * 0.28f, s * 0.81f, s * 0.20f, stroke);
        }

        private void drawPlay(Canvas c, float s) {
            tmpPath.reset();
            tmpPath.moveTo(s * 0.30f, s * 0.20f);
            tmpPath.lineTo(s * 0.78f, s * 0.50f);
            tmpPath.lineTo(s * 0.30f, s * 0.80f);
            tmpPath.close();
            c.drawPath(tmpPath, fill);
        }

        private void drawSkip(Canvas c, float s, boolean next) {
            tmpPath.reset();
            if (next) {
                tmpPath.moveTo(s * 0.22f, s * 0.24f);
                tmpPath.lineTo(s * 0.62f, s * 0.50f);
                tmpPath.lineTo(s * 0.22f, s * 0.76f);
                tmpPath.close();
                c.drawPath(tmpPath, fill);
                tmpRect.set(s * 0.68f, s * 0.24f, s * 0.78f, s * 0.76f);
                c.drawRoundRect(tmpRect, s * 0.03f, s * 0.03f, fill);
            } else {
                tmpPath.moveTo(s * 0.78f, s * 0.24f);
                tmpPath.lineTo(s * 0.38f, s * 0.50f);
                tmpPath.lineTo(s * 0.78f, s * 0.76f);
                tmpPath.close();
                c.drawPath(tmpPath, fill);
                tmpRect.set(s * 0.22f, s * 0.24f, s * 0.32f, s * 0.76f);
                c.drawRoundRect(tmpRect, s * 0.03f, s * 0.03f, fill);
            }
        }

        private void drawRooms(Canvas c, float s) {
            tmpRect.set(s * 0.16f, s * 0.16f, s * 0.44f, s * 0.44f);
            c.drawRoundRect(tmpRect, s * 0.07f, s * 0.07f, stroke);
            tmpRect.set(s * 0.56f, s * 0.16f, s * 0.84f, s * 0.44f);
            c.drawRoundRect(tmpRect, s * 0.07f, s * 0.07f, stroke);
            tmpRect.set(s * 0.16f, s * 0.56f, s * 0.44f, s * 0.84f);
            c.drawRoundRect(tmpRect, s * 0.07f, s * 0.07f, stroke);
            tmpRect.set(s * 0.56f, s * 0.56f, s * 0.84f, s * 0.84f);
            c.drawRoundRect(tmpRect, s * 0.07f, s * 0.07f, stroke);
        }

        private void drawSliders(Canvas c, float s) {
            c.drawLine(s * 0.18f, s * 0.30f, s * 0.82f, s * 0.30f, stroke);
            c.drawLine(s * 0.18f, s * 0.50f, s * 0.82f, s * 0.50f, stroke);
            c.drawLine(s * 0.18f, s * 0.70f, s * 0.82f, s * 0.70f, stroke);
            c.drawCircle(s * 0.38f, s * 0.30f, s * 0.08f, fill);
            c.drawCircle(s * 0.64f, s * 0.50f, s * 0.08f, fill);
            c.drawCircle(s * 0.44f, s * 0.70f, s * 0.08f, fill);
        }

        private void drawSend(Canvas c, float s) {
            tmpPath.reset();
            tmpPath.moveTo(s * 0.18f, s * 0.50f);
            tmpPath.lineTo(s * 0.82f, s * 0.20f);
            tmpPath.lineTo(s * 0.62f, s * 0.82f);
            tmpPath.lineTo(s * 0.48f, s * 0.56f);
            tmpPath.close();
            c.drawPath(tmpPath, fill);
        }

        private void drawVolume(Canvas c, float s) {
            tmpPath.reset();
            tmpPath.moveTo(s * 0.20f, s * 0.40f);
            tmpPath.lineTo(s * 0.36f, s * 0.40f);
            tmpPath.lineTo(s * 0.54f, s * 0.24f);
            tmpPath.lineTo(s * 0.54f, s * 0.76f);
            tmpPath.lineTo(s * 0.36f, s * 0.60f);
            tmpPath.lineTo(s * 0.20f, s * 0.60f);
            tmpPath.close();
            c.drawPath(tmpPath, fill);
            tmpRect.set(s * 0.54f, s * 0.32f, s * 0.76f, s * 0.68f);
            c.drawArc(tmpRect, -50f, 100f, false, stroke);
            tmpRect.set(s * 0.62f, s * 0.20f, s * 0.90f, s * 0.80f);
            c.drawArc(tmpRect, -50f, 100f, false, stroke);
        }

        @Override
        public void setAlpha(int alpha) {
            stroke.setAlpha(alpha);
            fill.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            stroke.setColorFilter(colorFilter);
            fill.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
