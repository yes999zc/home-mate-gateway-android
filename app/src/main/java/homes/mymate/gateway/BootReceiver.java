package homes.mymate.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Automatically starts {@link GatewayService} when the Android smart screen, 86 wall panel,
 * tablet, or speaker boots up.
 *
 * v2.0 Android 15+ compliance:
 * - Strictly validates boot action ({@code ACTION_BOOT_COMPLETED} / {@code QUICKBOOT_POWERON}).
 * - On Android 15+ (API 35+), {@code FOREGROUND_SERVICE_TYPE_MICROPHONE} and
 *   {@code FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK} cannot be started directly from
 *   {@code BOOT_COMPLETED}; posts a clean tap-to-resume notification instead of crashing.
 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "HomeMateBoot";
    private static final String BOOT_CHANNEL_ID = "home_mate_boot_resume";
    private static final int BOOT_NOTIFICATION_ID = 8933;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        boolean isBoot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action);
        if (!isBoot) {
            return;
        }
        GatewayConfig config = new GatewayConfig(context);
        if (!config.isAutoStartOnBoot()) {
            return;
        }

        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15+ prohibits starting microphone/mediaPlayback FGS directly from BOOT_COMPLETED
            postTapToResumeNotification(context, config.getLanguage());
            return;
        }

        Intent serviceIntent = new Intent(context, GatewayService.class);
        serviceIntent.putExtra(GatewayService.EXTRA_FROM_BOOT, true);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Boot FGS start restricted, falling back to notification: " + t.getMessage());
            postTapToResumeNotification(context, config.getLanguage());
        }
    }

    private static void postTapToResumeNotification(Context context, String lang) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        BOOT_CHANNEL_ID,
                        "Home Mate Boot Resume",
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(context, 1, launch, flags);
            Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(context, BOOT_CHANNEL_ID)
                    : new Notification.Builder(context);
            b.setContentTitle("Home Mate")
                    .setContentText(I18n.t(lang, "notif_boot_resume"))
                    .setSmallIcon(R.drawable.ic_stat_home_mate)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(BOOT_NOTIFICATION_ID, b.build());
        } catch (Throwable ignored) {
        }
    }
}
