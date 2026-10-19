package com.randomtrigger.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Scheduler.prefs(context).getBoolean(Scheduler.K_ENABLED, false)) {
            return;
        }

        String action = intent == null ? "" : intent.getAction();
        boolean regenerate = Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action);
        Scheduler.scheduleNext(context, regenerate);
    }
}
