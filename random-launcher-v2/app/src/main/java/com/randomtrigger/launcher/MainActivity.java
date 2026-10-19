package com.randomtrigger.launcher;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.card.MaterialCardView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Schedule dashboard, independent from FlightSearchActivity. */
public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_NOTIFICATIONS=901;
    private LinearLayout plans;
    private TextView stats;
    private TextView permissionStatus;
    private boolean firstDraw;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        PlanTrigger.ensureChannel(this);
        PlanStore.migrateOldPlan(this);
        PlanScheduler.rescheduleAll(this,false);
        setContentView(createScreen());
        firstDraw=true;
        refresh();
        android.content.SharedPreferences setup=getSharedPreferences("first_run_v3",MODE_PRIVATE);
        if(!setup.getBoolean("shown",false)) {
            setup.edit().putBoolean("shown",true).apply();
            new Handler(Looper.getMainLooper()).postDelayed(this::askPermissions,600);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if(firstDraw) refresh();
    }

    private View createScreen() {
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=col();
        root.setPadding(dp(18),dp(18),dp(18),dp(42));
        root.setBackgroundColor(Color.rgb(245,250,255));
        scroll.addView(root);

        TextView title=txt("机票查询",29,Color.rgb(12,62,112));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView subtitle=txt("Flight Search  ·  多计划独立调度",15,Color.GRAY);
        root.addView(subtitle);

        LinearLayout nav=row();
        Button flight=btn("✈ 机票查询");
        flight.setOnClickListener(v->startActivity(new Intent(this,FlightSearchActivity.class)));
        Button schedule=btn("⏰ 随机计划");
        schedule.setEnabled(false);
        nav.addView(flight,weight(1)); nav.addView(schedule,weight(1));
        root.addView(nav,full());

        TextView intro=txt("每条计划分别选择目标应用、时间段、执行星期。即使锁屏或关掉本页面，已设置的系统闹钟仍会保留。",14,Color.DKGRAY);
        intro.setPadding(0,dp(12),0,dp(8));
        root.addView(intro);

        LinearLayout heading=row();
        stats=txt("我的计划",21,Color.rgb(20,40,61));
        stats.setTypeface(Typeface.DEFAULT_BOLD);
        heading.addView(stats,weight(1));
        Button add=btn("+ 新建计划");
        add.setOnClickListener(v->editPlan(0));
        heading.addView(add,weight(1));
        root.addView(heading,full());

        plans=col();
        root.addView(plans,full());

        heading(root,"权限状态");
        permissionStatus=txt("",15,Color.DKGRAY);
        root.addView(permissionStatus);

        Button exact=btn("精确闹钟权限");
        exact.setOnClickListener(v->openExact());
        root.addView(exact,full());

        Button notify=btn("通知权限");
        notify.setOnClickListener(v->requestNotification());
        root.addView(notify,full());

        Button battery=btn("忽略电池优化");
        battery.setOnClickListener(v->requestBattery());
        root.addView(battery,full());

        Button realme=btn("realme 自启动和后台设置");
        realme.setOnClickListener(v->realmeSettings());
        root.addView(realme,full());

        TextView info=txt("特别提醒：realme 系统可能禁止锁屏时直接拉起第三方 App。已授权的计划会在到点时尝试启动，并提供通知兜底；无法绕过 PIN/指纹锁。",13,Color.GRAY);
        info.setPadding(0,dp(10),0,0);
        root.addView(info);
        return scroll;
    }

    private void refresh() {
        List<PlanStore.Plan> list=PlanStore.list(this);
        int enabled=0;
        for(PlanStore.Plan p:list) if(p.enabled) enabled++;
        stats.setText("我的计划  "+enabled+"/"+list.size()+" 运行中");
        plans.removeAllViews();
        if(list.isEmpty()) {
            MaterialCardView card=card();
            LinearLayout content=col();
            content.setPadding(dp(14),dp(18),dp(14),dp(18));
            content.addView(txt("还没有计划",18,Color.rgb(20,40,61)));
            content.addView(txt("点击「+ 新建计划」，设置第一条随机启动计划。",14,Color.GRAY));
            card.addView(content); plans.addView(card,full());
        }
        for(PlanStore.Plan p:list) addCard(p);
        updatePermission();
    }

    private void addCard(PlanStore.Plan p) {
        MaterialCardView card=card();
        LinearLayout content=col();
        content.setPadding(dp(12),dp(10),dp(12),dp(12));
        TextView title=txt(p.name()+(p.enabled ? "  ✓" : "  [已暂停]"),19,Color.rgb(16,60,100));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        content.addView(txt("目标 App："+p.appLabel,15,Color.DKGRAY));
        String span=String.format(Locale.CHINA,"%02d:%02d + 随机 %d～%d 分钟",
                p.hour,p.minute,p.minDelay,p.maxDelay);
        content.addView(txt(span,15,Color.DKGRAY));
        content.addView(txt("执行日："+weekString(p.weekdays),14,Color.DKGRAY));
        SimpleDateFormat format=new SimpleDateFormat("MM月dd日 HH:mm:ss",Locale.CHINA);
        String next=p.enabled && p.nextAt>0L ? format.format(new Date(p.nextAt)) : "无";
        content.addView(txt("下次触发："+next,14,Color.rgb(35,101,62)));

        LinearLayout controls=row();
        Button edit=btn("编辑");
        edit.setOnClickListener(v->editPlan(p.id));
        Button toggle=btn(p.enabled?"暂停":"启用");
        toggle.setOnClickListener(v->toggle(p));
        Button test=btn("测试");
        test.setOnClickListener(v->testPlan(p));
        Button delete=btn("删除");
        delete.setOnClickListener(v->deletePlan(p));
        controls.addView(edit,weight(1));
        controls.addView(toggle,weight(1));
        controls.addView(test,weight(1));
        controls.addView(delete,weight(1));
        content.addView(controls,full());
        card.addView(content);
        plans.addView(card,full());
    }

    private String weekString(int mask) {
        String[] names={"一","二","三","四","五","六","日"};
        StringBuilder sb=new StringBuilder();
        for(int i=0;i<7;i++) if((mask & (1<<i))!=0) {
            if(sb.length()>0) sb.append("、");
            sb.append(names[i]);
        }
        return sb.toString();
    }

    private void editPlan(int id) {
        Intent i=new Intent(this,PlanEditorActivity.class);
        i.putExtra(PlanEditorActivity.EXTRA_PLAN_ID,id);
        startActivity(i);
    }

    private void toggle(PlanStore.Plan p) {
        try {
            PlanScheduler.cancel(this,p.id);
            PlanStore.setEnabled(this,p.id,!p.enabled);
            if(!p.enabled) PlanScheduler.schedule(this,p.id,true);
            refresh();
        } catch(Exception e){toast("切换失败："+e.getMessage());}
    }

    private void deletePlan(PlanStore.Plan p) {
        new AlertDialog.Builder(this).setTitle("删除 "+p.name()+"？")
            .setMessage("只删除这一条计划，不影响其他计划。")
            .setNegativeButton("取消",null)
            .setPositiveButton("删除",(dialog,which)->{
                PlanScheduler.cancel(this,p.id);
                PlanStore.delete(this,p.id);
                PlanTrigger.cancel(this,p.id);
                refresh();
            }).show();
    }

    private void testPlan(PlanStore.Plan p) {
        try {
            long at=PlanScheduler.scheduleTest(this,p.id,30);
            if(at<=0L) {toast("目标应用不存在或计划信息不完整");return;}
            new AlertDialog.Builder(this).setTitle("30 秒锁屏测试")
                .setMessage(p.name()+" 将在 30 秒后触发。现在可以锁屏；这次测试不会改动正常计划或触发次数。")
                .setPositiveButton("确定",null).show();
        } catch(Exception e) {toast("安排测试失败："+e.getMessage());}
    }

    private void askPermissions() {
        if(Build.VERSION.SDK_INT>=33 &&
           checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                 !=PackageManager.PERMISSION_GRANTED) {
            requestNotification();
        } else showPermissionExplanation();
    }

    private void showPermissionExplanation() {
        new AlertDialog.Builder(this).setTitle("锁屏运行权限")
            .setMessage("需要精确闹钟、通知和电池优化豁免，才能尽可能在息屏时按计划触发。权限必须由你在系统设置中确认。")
            .setNegativeButton("稍后",null)
            .setPositiveButton("设置精确闹钟",(d,w)->openExact()).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions,@NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==REQUEST_NOTIFICATIONS) {
            updatePermission();
            showPermissionExplanation();
        }
    }

    private void updatePermission() {
        if(permissionStatus==null) return;
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        boolean battery=pm!=null && pm.isIgnoringBatteryOptimizations(getPackageName());
        boolean notifications=Build.VERSION.SDK_INT<33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText("通知："+mark(notifications)+
                "    精确闹钟："+mark(Scheduler.canScheduleExact(this))+
                "\n电池优化豁免："+mark(battery)+"    realme 自启动：需手动确认");
    }

    private String mark(boolean b) {return b?"✓":"待授权";}

    private void requestNotification() {
        if(Build.VERSION.SDK_INT>=33 &&
          checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQUEST_NOTIFICATIONS);
        else toast("通知权限已允许");
    }

    private void openExact() {
        if(Build.VERSION.SDK_INT<31 || Scheduler.canScheduleExact(this)) {
            toast("精确闹钟已经可用");return;
        }
        try {
            Intent i=new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:"+getPackageName()));
            startActivity(i);
        } catch(Exception ignored) {openDetails();}
    }

    private void requestBattery() {
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        if(pm!=null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast("电池优化豁免已允许");return;
        }
        try {
            Intent i=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:"+getPackageName()));
            startActivity(i);
        } catch(Exception e) {openDetails();}
    }

    private void realmeSettings() {
        String[][] candidates={
            {"com.coloros.safecenter","com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.oplus.safecenter","com.oplus.safecenter.startupapp.StartupAppListActivity"},
            {"com.oppo.safe","com.oppo.safe.permission.startup.StartupAppListActivity"}
        };
        for(String[] pair:candidates) try {
            Intent i=new Intent();i.setComponent(new ComponentName(pair[0],pair[1]));
            startActivity(i);return;
        } catch(Exception ignored){}
        openDetails();
    }
    private void openDetails() {
        Intent i=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:"+getPackageName()));
        startActivity(i);
    }
    private void heading(LinearLayout root,String text) {
        TextView v=txt(text,21,Color.rgb(12,62,112));
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0,dp(18),0,dp(8));
        root.addView(v);
    }
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private TextView txt(String t,int size,int color){TextView v=new TextView(this);v.setText(t);v.setTextSize(size);v.setTextColor(color);return v;}
    private Button btn(String t){Button b=new Button(this);b.setAllCaps(false);b.setText(t);b.setTextSize(13);b.setPadding(0,0,0,0);b.setMinWidth(0);b.setMinimumWidth(0);return b;}
    private MaterialCardView card(){
        MaterialCardView c=new MaterialCardView(this);
        c.setRadius(dp(13));c.setCardElevation(dp(2));c.setCardBackgroundColor(Color.WHITE);
        c.setUseCompatPadding(true);return c;
    }
    private LinearLayout.LayoutParams full(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);return p;}
    private LinearLayout.LayoutParams weight(int w){return new LinearLayout.LayoutParams(0,-2,w);}
    private int dp(int p){return Math.round(p*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
