package com.randomtrigger.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Each plan uses a distinct PendingIntent identity, not a shared single alarm. */
public final class PlanScheduler {
    public static final String EXTRA_PLAN_ID="plan_id";
    public static final String EXTRA_DUE_AT="due_at";
    public static final String EXTRA_IS_TEST="is_test";
    private static final Object LOCK=new Object();

    private PlanScheduler() {}

    private static PendingIntent pending(Context ctx,int id,boolean test,long at) {
        Intent i=new Intent(ctx,AlarmReceiver.class);
        // Data URI prevents Android PendingIntent matching two different tasks.
        i.setData(Uri.parse((test ? "flightsearch-test" : "flightsearch-plan")
                + "://alarm/" + id));
        i.putExtra(EXTRA_PLAN_ID,id);
        i.putExtra(EXTRA_IS_TEST,test);
        i.putExtra(EXTRA_DUE_AT,at);
        return PendingIntent.getBroadcast(ctx,id,i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void cancel(Context c,int planId) {
        synchronized(LOCK) {
            AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
            if(am!=null) {
                am.cancel(pending(c,planId,false,0L));
                am.cancel(pending(c,planId,true,0L));
            }
        }
    }

    private static void register(Context c,PendingIntent pi,long when) {
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        if(am==null) throw new IllegalStateException("系统闹钟服务不可用");
        try {
            if(Build.VERSION.SDK_INT>=23) {
                if(Scheduler.canScheduleExact(c)) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);
                }
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP,when,pi);
            }
        } catch(SecurityException denied) {
            if(Build.VERSION.SDK_INT>=23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP,when,pi);
            }
        }
    }

    private static class Slot {
        long at,baseDay;
        Slot(long time,long day) {at=time;baseDay=day;}
    }

    private static boolean onDay(int mask,DayOfWeek day) {
        int bit=day.getValue()-1;
        return (mask & (1<<bit))!=0;
    }

    private static Slot randomSlot(PlanStore.Plan p,long now) {
        ZoneId zone=ZoneId.systemDefault();
        LocalDate today=LocalDate.now(zone);
        for(int d=0;d<15;d++) {
            LocalDate date=today.plusDays(d);
            long baseDay=date.toEpochDay();
            if(!onDay(p.weekdays,date.getDayOfWeek()) || baseDay<=p.lastWindowDay) continue;
            long base=ZonedDateTime.of(date,LocalTime.of(p.hour,p.minute),zone)
                    .toInstant().toEpochMilli();
            long first=base+(long)p.minDelay*60_000L;
            long last=base+(long)p.maxDelay*60_000L+59_999L;
            long lower=Math.max(first,now+30_000L);
            if(lower>last) continue;
            long duration=last-lower;
            long randomOffset=duration<=0?0:ThreadLocalRandom.current().nextLong(duration+1L);
            return new Slot(lower+randomOffset,baseDay);
        }
        return null;
    }

    public static long schedule(Context c,int id,boolean regenerate) {
        synchronized(LOCK) {
            PlanStore.migrateOldPlan(c);
            PlanStore.Plan plan=PlanStore.find(c,id);
            if(plan==null || !plan.enabled || !plan.valid()) {
                cancel(c,id);
                return 0L;
            }
            long now=System.currentTimeMillis();
            if(!regenerate && plan.nextAt>now+1000L) {
                register(c,pending(c,id,false,plan.nextAt),plan.nextAt);
                return plan.nextAt;
            }
            if(!regenerate && plan.nextAt>0L && plan.nextAt<=now+1000L) {
                PlanStore.expireMissed(c,id,plan.nextAt);
                plan=PlanStore.find(c,id);
                if(plan==null) return 0L;
            }
            Slot slot=randomSlot(plan,now);
            if(slot==null) {
                cancel(c,id);
                PlanStore.setNextTime(c,id,0L,0L);
                return 0L;
            }
            // Save before registering alarm. If the app is killed, boot recovery sees it.
            PlanStore.setNextTime(c,id,slot.at,slot.baseDay);
            register(c,pending(c,id,false,slot.at),slot.at);
            return slot.at;
        }
    }

    public static void rescheduleAll(Context c,boolean regenerate) {
        synchronized(LOCK) {
            PlanStore.migrateOldPlan(c);
            List<PlanStore.Plan> all=PlanStore.list(c);
            for(PlanStore.Plan plan:all) {
                if(!plan.enabled) {
                    cancel(c,plan.id);
                } else {
                    try { schedule(c,plan.id,regenerate); }
                    catch(Exception ignored) { /* next system start/restart can retry */ }
                }
            }
        }
    }

    /** Test alarms do NOT change normal task state or create extra plan runs. */
    public static long scheduleTest(Context c,int id,int seconds) {
        PlanStore.Plan p=PlanStore.find(c,id);
        if(p==null || !p.valid()) return 0L;
        long at=System.currentTimeMillis()+Math.max(5,seconds)*1000L;
        register(c,pending(c,id,true,at),at);
        return at;
    }

    /** Returns null for disabled, stale, duplicate, or deleted alarms. */
    public static PlanStore.Plan acceptAlarm(Context c,int id,boolean test,long dueAt) {
        synchronized(LOCK) {
            PlanStore.Plan plan=PlanStore.find(c,id);
            if(plan==null || !plan.valid()) return null;
            if(test) return plan;
            if(!plan.enabled || plan.nextAt<=0L || plan.nextAt!=dueAt) return null;
            // Delivery should never be significantly premature.
            if(System.currentTimeMillis()+5000L<plan.nextAt) return null;
            if(!PlanStore.markTriggered(c,id,plan.nextAt)) return null;
            PlanStore.Plan snapshot=plan.copy();
            try {schedule(c,id,true);} catch(Exception ignored) {}
            return snapshot;
        }
    }
}
