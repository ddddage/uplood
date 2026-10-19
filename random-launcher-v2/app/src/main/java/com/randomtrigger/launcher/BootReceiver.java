package com.randomtrigger.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Restore all plan alarms after reboot, app update or local clock changes. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context,Intent intent) {
        final PendingResult pending=goAsync();
        Context app=context.getApplicationContext();
        new Thread(()->{
            try {
                boolean timeChanged=intent!=null &&
                    (Intent.ACTION_TIME_CHANGED.equals(intent.getAction())
                        || Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction()));
                PlanScheduler.rescheduleAll(app,timeChanged);
            } finally { pending.finish(); }
        },"restore-flight-plans").start();
    }
}
