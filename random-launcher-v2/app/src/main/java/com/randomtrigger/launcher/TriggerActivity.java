package com.randomtrigger.launcher;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

public class TriggerActivity extends AppCompatActivity {
    private String targetPackage;
    private String targetLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureLockScreenPresentation();
        readTarget(getIntent());
        setContentView(buildContent());

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isDeviceLocked()) {
                openTarget();
            }
        }, 700L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readTarget(intent);
    }

    private void configureLockScreenPresentation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
        }
    }

    private void readTarget(Intent intent) {
        targetPackage = intent == null ? null : intent.getStringExtra(Scheduler.K_PACKAGE);
        targetLabel = intent == null ? null : intent.getStringExtra(Scheduler.K_LABEL);

        if (targetPackage == null || targetPackage.trim().isEmpty()) {
            targetPackage = Scheduler.prefs(this).getString(Scheduler.K_PACKAGE, "");
        }
        if (targetLabel == null || targetLabel.trim().isEmpty()) {
            targetLabel = Scheduler.prefs(this).getString(Scheduler.K_LABEL, targetPackage);
        }
    }

    private LinearLayout buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(44), dp(28), dp(28));
        root.setBackgroundColor(Color.rgb(247, 249, 252));

        TextView title = new TextView(this);
        title.setText("随机触发时间到了");
        title.setTextSize(27f);
        title.setTextColor(Color.rgb(23, 32, 51));
        title.setGravity(Gravity.CENTER);

        TextView target = new TextView(this);
        target.setText("\n目标应用：" + (targetLabel == null ? targetPackage : targetLabel));
        target.setTextSize(19f);
        target.setTextColor(Color.rgb(35, 68, 102));
        target.setGravity(Gravity.CENTER);

        TextView explanation = new TextView(this);
        explanation.setText(
                "\n如果手机没有安全锁，会自动尝试打开目标应用。"
                        + "\n如果设置了 PIN、图案或指纹，Android 不允许第三方应用绕过锁屏；解锁后会继续打开。"
        );
        explanation.setTextSize(16f);
        explanation.setTextColor(Color.DKGRAY);
        explanation.setGravity(Gravity.CENTER);

        Button open = new Button(this);
        open.setText("解锁并打开目标应用");
        open.setAllCaps(false);
        open.setOnClickListener(v -> dismissKeyguardAndOpen());

        Button close = new Button(this);
        close.setText("关闭提醒");
        close.setAllCaps(false);
        close.setOnClickListener(v -> {
            cancelTriggerNotification();
            finish();
        });

        root.addView(title);
        root.addView(target);
        root.addView(explanation);
        root.addView(open, buttonLayout());
        root.addView(close, buttonLayout());
        return root;
    }

    private void dismissKeyguardAndOpen() {
        KeyguardManager manager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (manager == null || !manager.isKeyguardLocked()) {
            openTarget();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    openTarget();
                }

                @Override
                public void onDismissError() {
                    Toast.makeText(TriggerActivity.this, "系统未允许解除锁屏", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            Toast.makeText(this, "请先手动解锁手机", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isDeviceLocked() {
        KeyguardManager manager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return manager != null && manager.isKeyguardLocked();
    }

    private void openTarget() {
        if (targetPackage == null || targetPackage.trim().isEmpty()) {
            Toast.makeText(this, "没有设置目标应用", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        cancelTriggerNotification();
        if (TriggerService.launchTarget(this, targetPackage)) {
            finish();
        } else {
            Toast.makeText(this, "无法启动目标应用，请重新选择", Toast.LENGTH_LONG).show();
        }
    }

    private void cancelTriggerNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(TriggerService.NOTIFICATION_ID);
        }
    }

    @NonNull
    private LinearLayout.LayoutParams buttonLayout() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(18);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
