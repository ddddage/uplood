package com.randomtrigger.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class AlarmReceiver extends BroadcastReceiver {
    public static final String EXTRA_TEST = "extra_test_trigger";

    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pendingResult = goAsync();
        final boolean test = intent != null && intent.getBooleanExtra(EXTRA_TEST, false);
        final Context appContext = context.getApplicationContext();

        new Thread(() -> {
            try {
                Intent serviceIntent = new Intent(appContext, TriggerService.class);
                serviceIntent.putExtra(EXTRA_TEST, test);
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appContext.startForegroundService(serviceIntent);
                    } else {
                        appContext.startService(serviceIntent);
                    }
                } catch (Throwable serviceStartFailure) {
                    TriggerService.postAlarmNotification(appContext);
                }

                if (!test) {
                    Scheduler.markTriggeredAndScheduleFollowing(appContext);
                }
            } finally {
                pendingResult.finish();
            }
        }, "random-launcher-alarm").start();
    }
}
