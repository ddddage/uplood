package com.randomtrigger.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;

public final class Scheduler {
    public static final String PREFS = "random_launcher_v2";
    public static final String K_ENABLED = "enabled";
    public static final String K_PACKAGE = "target_package";
    public static final String K_LABEL = "target_label";
    public static final String K_HOUR = "base_hour";
    public static final String K_MINUTE = "base_minute";
    public static final String K_RAND_MIN = "random_min_minutes";
    public static final String K_RAND_MAX = "random_max_minutes";
    public static final String K_DAYS = "week_mask";
    public static final String K_NEXT = "next_trigger_at";
    public static final String K_LAST = "last_trigger_at";
    public static final String K_LOCK_DIRECT = "direct_launch_while_locked";
    public static final String K_WIZARD_SHOWN = "permission_wizard_shown";

    private static final int REQUEST_MAIN = 1001;
    private static final int REQUEST_TEST = 2002;

    private Scheduler() {
    }

    public static Context storage(Context context) {
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return app.createDeviceProtectedStorageContext();
        }
        return app;
    }

    public static SharedPreferences prefs(Context context) {
        return storage(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static PendingIntent alarmIntent(Context context, int requestCode, boolean test) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.putExtra(AlarmReceiver.EXTRA_TEST, test);
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    public static boolean canScheduleExact(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return alarmManager != null && alarmManager.canScheduleExactAlarms();
    }

    public static void cancelMain(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.cancel(alarmIntent(context, REQUEST_MAIN, false));
        }
    }

    public static long scheduleNext(Context context, boolean regenerate) {
        SharedPreferences preferences = prefs(context);
        if (!preferences.getBoolean(K_ENABLED, false)) {
            preferences.edit().putLong(K_NEXT, 0L).apply();
            cancelMain(context);
            return 0L;
        }

        String targetPackage = preferences.getString(K_PACKAGE, "");
        if (targetPackage == null || targetPackage.trim().isEmpty()) {
            preferences.edit().putLong(K_NEXT, 0L).apply();
            cancelMain(context);
            return 0L;
        }

        long now = System.currentTimeMillis();
        long triggerAt = preferences.getLong(K_NEXT, 0L);
        if (regenerate || triggerAt <= now + 5_000L) {
            triggerAt = generateNext(preferences, now);
            if (triggerAt <= 0L) {
                preferences.edit().putLong(K_NEXT, 0L).apply();
                cancelMain(context);
                return 0L;
            }
            preferences.edit().putLong(K_NEXT, triggerAt).apply();
        }

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return 0L;
        }

        PendingIntent pendingIntent = alarmIntent(context, REQUEST_MAIN, false);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (canScheduleExact(context)) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            }
        } catch (SecurityException exactAlarmDenied) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        }
        return triggerAt;
    }

    public static long scheduleTest(Context context, int delaySeconds) {
        long triggerAt = System.currentTimeMillis() + Math.max(5, delaySeconds) * 1_000L;
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return 0L;
        }

        PendingIntent pendingIntent = alarmIntent(context, REQUEST_TEST, true);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (canScheduleExact(context)) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            }
        } catch (SecurityException exactAlarmDenied) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        }
        return triggerAt;
    }

    public static void markTriggeredAndScheduleFollowing(Context context) {
        long now = System.currentTimeMillis();
        prefs(context).edit()
                .putLong(K_LAST, now)
                .putLong(K_NEXT, 0L)
                .apply();
        scheduleNext(context, true);
    }

    private static long generateNext(SharedPreferences preferences, long nowMs) {
        int hour = clamp(preferences.getInt(K_HOUR, 8), 0, 23);
        int minute = clamp(preferences.getInt(K_MINUTE, 30), 0, 59);
        int randomMin = clamp(preferences.getInt(K_RAND_MIN, 0), 0, 1_440);
        int randomMax = clamp(preferences.getInt(K_RAND_MAX, 30), randomMin, 1_440);
        int weekMask = preferences.getInt(K_DAYS, 31);
        if (weekMask == 0) {
            return 0L;
        }

        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);

        for (int dayOffset = 0; dayOffset < 21; dayOffset++) {
            LocalDate date = today.plusDays(dayOffset);
            if (!isAllowedDay(weekMask, date.getDayOfWeek())) {
                continue;
            }

            LocalDateTime baseDateTime = LocalDateTime.of(date, LocalTime.of(hour, minute));
            long baseMs = baseDateTime.atZone(zone).toInstant().toEpochMilli();
            long earliest = baseMs + randomMin * 60_000L;
            long latest = baseMs + randomMax * 60_000L + 59_999L;
            long effectiveStart = Math.max(earliest, nowMs + 30_000L);
            if (effectiveStart > latest) {
                continue;
            }

            long range = latest - effectiveStart;
            long offset = range <= 0L ? 0L : ThreadLocalRandom.current().nextLong(range + 1L);
            return effectiveStart + offset;
        }
        return 0L;
    }

    private static boolean isAllowedDay(int mask, DayOfWeek dayOfWeek) {
        int bit;
        switch (dayOfWeek) {
            case MONDAY:
                bit = 0;
                break;
            case TUESDAY:
                bit = 1;
                break;
            case WEDNESDAY:
                bit = 2;
                break;
            case THURSDAY:
                bit = 3;
                break;
            case FRIDAY:
                bit = 4;
                break;
            case SATURDAY:
                bit = 5;
                break;
            case SUNDAY:
            default:
                bit = 6;
                break;
        }
        return (mask & (1 << bit)) != 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
