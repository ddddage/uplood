package com.randomtrigger.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/** Deliver each task by ID, reject cancelled or stale alarm broadcasts. */
public final class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context,Intent intent) {
        if(intent==null) return;
        final PendingResult pending=goAsync();
        final Context app=context.getApplicationContext();
        final int id=intent.getIntExtra(PlanScheduler.EXTRA_PLAN_ID,-1);
        final boolean test=intent.getBooleanExtra(PlanScheduler.EXTRA_IS_TEST,false);
        final long due=intent.getLongExtra(PlanScheduler.EXTRA_DUE_AT,0L);
        new Thread(()->{
            PowerManager.WakeLock wl=null;
            try {
                PowerManager pm=(PowerManager)app.getSystemService(Context.POWER_SERVICE);
                if(pm!=null) {
                    wl=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"FlightSearch:plan:"+id);
                    wl.acquire(9000L);
                }
                PlanStore.Plan plan=PlanScheduler.acceptAlarm(app,id,test,due);
                if(plan!=null) PlanTrigger.trigger(app,plan);
            } catch(Exception ignored) {
                PlanStore.Plan p=PlanStore.find(app,id);
                if(p!=null) PlanTrigger.trigger(app,p);
            } finally {
                if(wl!=null && wl.isHeld()) wl.release();
                pending.finish();
            }
        },"plan-alarm-"+id).start();
    }
}
