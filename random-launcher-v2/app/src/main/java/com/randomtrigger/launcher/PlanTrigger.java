package com.randomtrigger.launcher;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/** Per-plan notification and app launch. Does not manipulate target app UI. */
public final class PlanTrigger {
    private static final String CHANNEL = "flight_search_plan_alerts_v3";
    public static final String EXTRA_ID="plan_id";

    private PlanTrigger() {}

    private static int notificationId(int id) { return 20000+id; }

    public static void ensureChannel(Context c) {
        if(Build.VERSION.SDK_INT<26) return;
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(nm==null) return;
        NotificationChannel channel=new NotificationChannel(CHANNEL,"计划触发提醒",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("为锁屏和后台运行的计划提供提醒");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(channel);
    }

    public static boolean launch(Context c,String pkg) {
        try {
            Intent intent=c.getPackageManager().getLaunchIntentForPackage(pkg);
            if(intent==null) return false;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            c.startActivity(intent);
            return true;
        } catch(Exception e) {return false;}
    }

    public static void cancel(Context c,int id) {
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(nm!=null) nm.cancel(notificationId(id));
    }

    public static void trigger(Context c,PlanStore.Plan plan) {
        ensureChannel(c);
        KeyguardManager km=(KeyguardManager)c.getSystemService(Context.KEYGUARD_SERVICE);
        boolean locked=km!=null && km.isKeyguardLocked();
        boolean attempted = false;
        if(!locked || plan.directWhenLocked) attempted=launch(c,plan.packageName);
        // Also show a fallback because some OEMs silently suppress startActivity.
        notifyUser(c,plan,locked,attempted);
    }

    private static void notifyUser(Context c,PlanStore.Plan p,boolean locked,boolean attempted) {
        if(Build.VERSION.SDK_INT>=33 &&
                c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        !=PackageManager.PERMISSION_GRANTED) return;
        Intent open=new Intent(c,TriggerActivity.class);
        open.setData(Uri.parse("flightsearch-action://plan/"+p.id));
        open.putExtra(EXTRA_ID,p.id);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent action=PendingIntent.getActivity(c,10000+p.id,open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String status=locked ? "手机处于锁屏状态，请解锁确认"
                : (attempted ? "已尝试启动，若未弹出请点此打开" : "系统阻止自动启动，请点此打开");
        NotificationCompat.Builder b=new NotificationCompat.Builder(c,CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_myplaces)
                .setContentTitle(p.name()+" · "+p.appLabel)
                .setContentText(status)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        p.name()+" 的随机时间已到。"+status+"。本应用不会绕过安全锁屏。"))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(action)
                .setFullScreenIntent(action,true)
                .addAction(android.R.drawable.ic_menu_view,"打开应用",action)
                .setAutoCancel(true);
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(nm!=null) try {nm.notify(notificationId(p.id),b.build());}
        catch(SecurityException ignored) {}
    }
}
