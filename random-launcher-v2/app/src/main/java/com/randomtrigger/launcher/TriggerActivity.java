package com.randomtrigger.launcher;

import android.app.KeyguardManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

/** Full-screen lockscreen-safe prompt for one specific plan. */
public class TriggerActivity extends AppCompatActivity {
    private int planId;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(Build.VERSION.SDK_INT>=27) {setShowWhenLocked(true);setTurnScreenOn(true);}
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        planId=getIntent().getIntExtra(PlanTrigger.EXTRA_ID,-1);
        PlanStore.Plan p=PlanStore.find(this,planId);
        if(p==null) {finish();return;}
        LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22),dp(40),dp(22),dp(20));
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title=new TextView(this);
        title.setText("计划触发："+p.name());
        title.setTextSize(24); title.setGravity(Gravity.CENTER);
        TextView explain=new TextView(this);
        explain.setText("\n目标："+p.appLabel+"\n\n系统若要求 PIN、密码或指纹，请先解锁。本应用不会绕过锁屏或操作目标 App 内的按钮。");
        explain.setTextSize(17); explain.setGravity(Gravity.CENTER);
        Button open=new Button(this);
        open.setText("解锁并打开 "+p.appLabel);
        open.setOnClickListener(v->unlockAndOpen(p));
        Button dismiss=new Button(this);
        dismiss.setText("关闭提醒");
        dismiss.setOnClickListener(v->{PlanTrigger.cancel(this,planId);finish();});
        layout.addView(title);layout.addView(explain);layout.addView(open);layout.addView(dismiss);
        setContentView(layout);
    }
    private void unlockAndOpen(PlanStore.Plan plan) {
        KeyguardManager km=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        if(km!=null && km.isKeyguardLocked() && Build.VERSION.SDK_INT>=26) {
            km.requestDismissKeyguard(this,new KeyguardManager.KeyguardDismissCallback(){
                @Override public void onDismissSucceeded(){open(plan);}
                @Override public void onDismissError(){Toast.makeText(
                    TriggerActivity.this,"请先手动解锁",Toast.LENGTH_SHORT).show();}
            });
        } else if(km!=null && km.isKeyguardLocked()) {
            Toast.makeText(this,"请先解锁设备",Toast.LENGTH_SHORT).show();
        } else open(plan);
    }
    private void open(PlanStore.Plan plan){
        if(PlanTrigger.launch(this,plan.packageName)){
            PlanTrigger.cancel(this,planId);finish();
        } else Toast.makeText(this,"无法打开目标应用",Toast.LENGTH_LONG).show();
    }
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
}
