package com.randomtrigger.launcher;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class TriggerService extends Service {
    public static final String CHANNEL_ID = "random_trigger_alarm_v2";
    public static final int NOTIFICATION_ID = 4104;

    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences preferences = Scheduler.prefs(this);
        String targetPackage = preferences.getString(Scheduler.K_PACKAGE, "");
        String targetLabel = preferences.getString(Scheduler.K_LABEL, targetPackage);

        if (targetPackage == null || targetPackage.trim().isEmpty()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification(this, targetPackage, targetLabel);
        startForeground(NOTIFICATION_ID, notification);
        acquireWakeLock();

        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean locked = keyguardManager != null && keyguardManager.isKeyguardLocked();
        boolean directWhenLocked = preferences.getBoolean(Scheduler.K_LOCK_DIRECT, true);

        final boolean launched = (!locked || directWhenLocked) && launchTarget(this, targetPackage);
        final boolean leaveReminder = locked || !launched;

        handler.postDelayed(() -> {
            if (!leaveReminder) {
                NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (manager != null) {
                    manager.cancel(NOTIFICATION_ID);
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH);
            } else {
                stopForeground(false);
            }
            stopSelf(startId);
        }, leaveReminder ? 15_000L : 6_000L);

        return START_NOT_STICKY;
    }

    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "RandomLauncher:TriggerWakeLock"
                );
                wakeLock.acquire(25_000L);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void postAlarmNotification(Context context) {
        SharedPreferences preferences = Scheduler.prefs(context);
        String targetPackage = preferences.getString(Scheduler.K_PACKAGE, "");
        String targetLabel = preferences.getString(Scheduler.K_LABEL, targetPackage);
        if (targetPackage == null || targetPackage.trim().isEmpty()) {
            return;
        }

        ensureChannel(context);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            try {
                manager.notify(NOTIFICATION_ID, buildNotification(context, targetPackage, targetLabel));
            } catch (SecurityException ignored) {
            }
        }
    }

    public static Notification buildNotification(Context context, String targetPackage, String targetLabel) {
        ensureChannel(context);
        String safeLabel = targetLabel == null || targetLabel.trim().isEmpty()
                ? targetPackage
                : targetLabel;

        Intent triggerIntent = new Intent(context, TriggerActivity.class);
        triggerIntent.putExtra(Scheduler.K_PACKAGE, targetPackage);
        triggerIntent.putExtra(Scheduler.K_LABEL, safeLabel);
        triggerIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );

        PendingIntent fullScreenIntent = PendingIntent.getActivity(
                context,
                3103,
                triggerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("随机启动时间到了")
                .setContentText("目标：" + safeLabel + "。若未自动打开，请解锁或点此打开。")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        "目标：" + safeLabel + "。Android 不允许应用绕过安全锁屏；若系统阻止后台直接启动，请解锁或点击这条提醒。"
                ))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(false)
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60_000L)
                .setContentIntent(fullScreenIntent)
                .setFullScreenIntent(fullScreenIntent, true)
                .addAction(android.R.drawable.ic_menu_view, "打开目标应用", fullScreenIntent)
                .build();
    }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.trigger_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(context.getString(R.string.trigger_channel_description));
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.enableVibration(true);
        manager.createNotificationChannel(channel);
    }

    public static boolean launchTarget(Context context, String targetPackage) {
        try {
            PackageManager packageManager = context.getPackageManager();
            Intent launchIntent = packageManager.getLaunchIntentForPackage(targetPackage);
            if (launchIntent == null) {
                return false;
            }
            launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
            );
            context.startActivity(launchIntent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
