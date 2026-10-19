package com.randomtrigger.launcher;

import android.Manifest;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_NOTIFICATION = 9001;
    private static final int ATTEMPT_NOTIFICATION = 1;
    private static final int ATTEMPT_EXACT_ALARM = 1 << 1;
    private static final int ATTEMPT_BATTERY = 1 << 2;

    private SharedPreferences preferences;
    private Button appButton;
    private Button timeButton;
    private EditText minDelayInput;
    private EditText maxDelayInput;
    private final CheckBox[] dayChecks = new CheckBox[7];
    private CheckBox enabledCheck;
    private CheckBox directLockedCheck;
    private TextView scheduleStatus;
    private TextView permissionStatus;
    private int baseHour;
    private int baseMinute;

    private boolean permissionFlowActive;
    private int permissionAttempts;
    private boolean activityCreated;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = Scheduler.prefs(this);
        TriggerService.ensureChannel(this);

        baseHour = preferences.getInt(Scheduler.K_HOUR, 8);
        baseMinute = preferences.getInt(Scheduler.K_MINUTE, 30);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        setContentView(buildScreen());
        activityCreated = true;
        refreshAll();

        if (!preferences.getBoolean(Scheduler.K_WIZARD_SHOWN, false)) {
            preferences.edit().putBoolean(Scheduler.K_WIZARD_SHOWN, true).apply();
            new Handler(Looper.getMainLooper()).postDelayed(this::beginFirstRunSetup, 600L);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (activityCreated) {
            refreshAll();
        }
        if (permissionFlowActive) {
            new Handler(Looper.getMainLooper()).postDelayed(this::continuePermissionFlow, 450L);
        }
    }

    private View buildScreen() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(48));
        root.setBackgroundColor(Color.rgb(247, 249, 252));
        scrollView.addView(root);

        TextView title = text("随机启动器", 29, Color.rgb(23, 32, 51));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = text(
                "适配 realme / Android 13。每天在你设定的随机区间触发所选 App；计划由系统闹钟保存，App 被划掉或手机重启后会恢复。",
                15,
                Color.DKGRAY
        );
        subtitle.setPadding(0, dp(8), 0, dp(12));
        root.addView(subtitle);

        addSection(root, "目标应用");
        appButton = button("选择第三方 App");
        appButton.setOnClickListener(v -> chooseTargetApp());
        root.addView(appButton, fullWidth());

        addSection(root, "触发时间");
        timeButton = button("");
        timeButton.setOnClickListener(v -> new TimePickerDialog(
                this,
                (view, hourOfDay, minute) -> {
                    baseHour = hourOfDay;
                    baseMinute = minute;
                    refreshTimeButton();
                },
                baseHour,
                baseMinute,
                true
        ).show());
        root.addView(timeButton, fullWidth());

        TextView randomHint = text(
                "实际触发时间 = 基准时间 + 随机延迟。例如基准 08:30、随机 0～25 分钟，会在 08:30:00～08:55:59 之间随机。",
                14,
                Color.DKGRAY
        );
        randomHint.setPadding(0, dp(8), 0, dp(6));
        root.addView(randomHint);

        minDelayInput = numberInput(String.valueOf(preferences.getInt(Scheduler.K_RAND_MIN, 0)));
        maxDelayInput = numberInput(String.valueOf(preferences.getInt(Scheduler.K_RAND_MAX, 30)));
        root.addView(labeledInput("最小随机延迟（分钟）", minDelayInput));
        root.addView(labeledInput("最大随机延迟（分钟）", maxDelayInput));

        addSection(root, "执行星期");
        GridLayout dayGrid = new GridLayout(this);
        dayGrid.setColumnCount(4);
        String[] dayNames = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        int dayMask = preferences.getInt(Scheduler.K_DAYS, 31);
        for (int i = 0; i < dayChecks.length; i++) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(dayNames[i]);
            checkBox.setChecked((dayMask & (1 << i)) != 0);
            dayChecks[i] = checkBox;
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.columnSpec = GridLayout.spec(i % 4, 1f);
            params.setGravity(Gravity.FILL_HORIZONTAL);
            dayGrid.addView(checkBox, params);
        }
        root.addView(dayGrid, fullWidth());

        enabledCheck = new CheckBox(this);
        enabledCheck.setText("启用随机计划");
        enabledCheck.setTextSize(16f);
        enabledCheck.setChecked(preferences.getBoolean(Scheduler.K_ENABLED, false));
        root.addView(enabledCheck);

        directLockedCheck = new CheckBox(this);
        directLockedCheck.setText("锁屏时也尝试直接拉起目标 App（失败会用锁屏提醒兜底）");
        directLockedCheck.setChecked(preferences.getBoolean(Scheduler.K_LOCK_DIRECT, true));
        root.addView(directLockedCheck);

        Button saveButton = button("保存并重新生成随机时间");
        saveButton.setOnClickListener(v -> saveAndSchedule());
        root.addView(saveButton, fullWidth());

        scheduleStatus = text("", 16, Color.rgb(35, 100, 58));
        scheduleStatus.setPadding(0, dp(12), 0, 0);
        root.addView(scheduleStatus);

        addSection(root, "实机测试");
        Button openNowButton = button("立即打开目标 App");
        openNowButton.setOnClickListener(v -> openTargetNow());
        root.addView(openNowButton, fullWidth());

        Button testButton = button("30 秒后测试锁屏触发");
        testButton.setOnClickListener(v -> scheduleLockScreenTest());
        root.addView(testButton, fullWidth());

        addSection(root, "权限与 realme 后台设置");
        permissionStatus = text("", 15, Color.DKGRAY);
        root.addView(permissionStatus);

        Button wizardButton = button("运行权限设置向导");
        wizardButton.setOnClickListener(v -> startPermissionFlow());
        root.addView(wizardButton, fullWidth());

        Button exactButton = button("打开“闹钟和提醒 / 精确闹钟”");
        exactButton.setOnClickListener(v -> openExactAlarmSettings());
        root.addView(exactButton, fullWidth());

        Button batteryButton = button("请求忽略电池优化");
        batteryButton.setOnClickListener(v -> requestBatteryExemption());
        root.addView(batteryButton, fullWidth());

        Button realmeButton = button("打开 realme 自启动 / 后台设置");
        realmeButton.setOnClickListener(v -> openRealmeSettings());
        root.addView(realmeButton, fullWidth());

        Button appSettingsButton = button("打开本应用系统详情");
        appSettingsButton.setOnClickListener(v -> openAppDetails());
        root.addView(appSettingsButton, fullWidth());

        TextView note = text(
                "\nrealme 上还要手动确认：允许后台活动、允许自动启动/自启动、允许本应用启动其他应用（如系统有此项），并把电池使用设为“不限制”。\n\n安全说明：本应用不会绕过 PIN、图案或指纹锁，也不会模拟点击、伪造位置或改动目标 App 数据。",
                14,
                Color.DKGRAY
        );
        root.addView(note);

        return scrollView;
    }

    private void beginFirstRunSetup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION);
        } else {
            showFirstRunExplanation();
        }
    }

    private void showFirstRunExplanation() {
        new AlertDialog.Builder(this)
                .setTitle("先完成锁屏触发权限")
                .setMessage(
                        "为了让随机计划在息屏、锁屏和省电状态下尽量可靠，需要通知、精确闹钟和忽略电池优化权限。"
                                + "\n\n向导只会打开系统授权页，不会偷偷修改设置。"
                )
                .setNegativeButton("稍后", null)
                .setPositiveButton("开始授权", (dialog, which) -> startPermissionFlow())
                .show();
    }

    private void startPermissionFlow() {
        permissionFlowActive = true;
        permissionAttempts = 0;
        continuePermissionFlow();
    }

    private void continuePermissionFlow() {
        if (!permissionFlowActive || isFinishing()) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && (permissionAttempts & ATTEMPT_NOTIFICATION) == 0) {
            permissionAttempts |= ATTEMPT_NOTIFICATION;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION);
            return;
        }

        if (!Scheduler.canScheduleExact(this)
                && (permissionAttempts & ATTEMPT_EXACT_ALARM) == 0) {
            permissionAttempts |= ATTEMPT_EXACT_ALARM;
            openExactAlarmSettings();
            return;
        }

        if (!isIgnoringBatteryOptimizations()
                && (permissionAttempts & ATTEMPT_BATTERY) == 0) {
            permissionAttempts |= ATTEMPT_BATTERY;
            requestBatteryExemption();
            return;
        }

        permissionFlowActive = false;
        refreshPermissions();
        new AlertDialog.Builder(this)
                .setTitle("系统权限步骤已完成")
                .setMessage(
                        "下一步请在 realme 设置里确认：后台活动、自启动、允许启动其他应用、电池不限制。"
                                + "\n\n若某项刚才被你拒绝，可在首页单独重新打开。"
                )
                .setNegativeButton("返回首页", null)
                .setPositiveButton("打开 realme 设置", (dialog, which) -> openRealmeSettings())
                .show();
    }

    private void saveAndSchedule() {
        if (!persistForm(true)) {
            return;
        }

        long next = Scheduler.scheduleNext(this, true);
        refreshScheduleStatus();
        refreshPermissions();

        if (!enabledCheck.isChecked()) {
            toast("设置已保存，随机计划处于停用状态");
            return;
        }
        if (next <= 0L) {
            toast("没有生成下一次计划，请检查目标 App 和星期设置");
            return;
        }
        toast("已保存并安排下一次随机触发");

        if (!Scheduler.canScheduleExact(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("还缺少精确闹钟权限")
                    .setMessage("没有该权限时系统可能延迟触发。现在去授权？")
                    .setNegativeButton("暂不", null)
                    .setPositiveButton("去授权", (dialog, which) -> openExactAlarmSettings())
                    .show();
        }
    }

    private boolean persistForm(boolean requireWeekday) {
        String targetPackage = preferences.getString(Scheduler.K_PACKAGE, "");
        if (targetPackage == null || targetPackage.trim().isEmpty()) {
            toast("请先选择目标 App");
            return false;
        }

        int minDelay;
        int maxDelay;
        try {
            minDelay = Integer.parseInt(minDelayInput.getText().toString().trim());
            maxDelay = Integer.parseInt(maxDelayInput.getText().toString().trim());
        } catch (NumberFormatException error) {
            toast("随机延迟请输入整数分钟");
            return false;
        }

        if (minDelay < 0 || maxDelay < minDelay || maxDelay > 1_440) {
            toast("随机范围要求：0 ≤ 最小值 ≤ 最大值 ≤ 1440 分钟");
            return false;
        }

        int dayMask = 0;
        for (int i = 0; i < dayChecks.length; i++) {
            if (dayChecks[i].isChecked()) {
                dayMask |= 1 << i;
            }
        }
        if (requireWeekday && dayMask == 0) {
            toast("至少选择一个执行星期");
            return false;
        }

        preferences.edit()
                .putInt(Scheduler.K_HOUR, baseHour)
                .putInt(Scheduler.K_MINUTE, baseMinute)
                .putInt(Scheduler.K_RAND_MIN, minDelay)
                .putInt(Scheduler.K_RAND_MAX, maxDelay)
                .putInt(Scheduler.K_DAYS, dayMask)
                .putBoolean(Scheduler.K_ENABLED, enabledCheck.isChecked())
                .putBoolean(Scheduler.K_LOCK_DIRECT, directLockedCheck.isChecked())
                .apply();

        if (!enabledCheck.isChecked()) {
            preferences.edit().putLong(Scheduler.K_NEXT, 0L).apply();
            Scheduler.cancelMain(this);
        }
        return true;
    }

    private void chooseTargetApp() {
        Intent queryIntent = new Intent(Intent.ACTION_MAIN);
        queryIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(
                queryIntent,
                PackageManager.MATCH_ALL
        );

        Map<String, AppEntry> unique = new LinkedHashMap<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) {
                continue;
            }
            String packageName = info.activityInfo.packageName;
            if (getPackageName().equals(packageName)) {
                continue;
            }
            String label = String.valueOf(info.loadLabel(getPackageManager()));
            unique.put(packageName, new AppEntry(label, packageName));
        }

        List<AppEntry> apps = new ArrayList<>(unique.values());
        Collections.sort(apps, (left, right) -> left.label.compareToIgnoreCase(right.label));
        if (apps.isEmpty()) {
            toast("没有读取到可启动的第三方 App");
            return;
        }

        String[] rows = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppEntry app = apps.get(i);
            rows[i] = app.label + "\n" + app.packageName;
        }

        new AlertDialog.Builder(this)
                .setTitle("选择目标 App")
                .setItems(rows, (dialog, index) -> {
                    AppEntry selected = apps.get(index);
                    preferences.edit()
                            .putString(Scheduler.K_PACKAGE, selected.packageName)
                            .putString(Scheduler.K_LABEL, selected.label)
                            .apply();
                    refreshAppButton();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openTargetNow() {
        String targetPackage = preferences.getString(Scheduler.K_PACKAGE, "");
        if (targetPackage == null || targetPackage.trim().isEmpty()) {
            toast("请先选择目标 App");
            return;
        }
        if (!TriggerService.launchTarget(this, targetPackage)) {
            toast("无法启动该 App，请重新选择");
        }
    }

    private void scheduleLockScreenTest() {
        if (!persistForm(false)) {
            return;
        }
        long triggerAt = Scheduler.scheduleTest(this, 30);
        if (triggerAt <= 0L) {
            toast("测试安排失败");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("30 秒锁屏测试已安排")
                .setMessage("现在按电源键锁屏。到点后应尝试打开目标 App；若系统拦截，应显示锁屏提醒。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || Scheduler.canScheduleExact(this)) {
            toast("精确闹钟权限已经可用");
            return;
        }
        try {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        } catch (Throwable error) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM));
            } catch (Throwable ignored) {
                openAppDetails();
            }
        }
    }

    private void requestBatteryExemption() {
        if (isIgnoringBatteryOptimizations()) {
            toast("本应用已经被允许忽略电池优化");
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable error) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) {
                openAppDetails();
            }
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void openRealmeSettings() {
        toast("请确认后台活动、自启动、允许启动其他应用和电池不限制");
        String[][] candidates = {
                {"com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"}
        };

        for (String[] candidate : candidates) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(candidate[0], candidate[1]));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        openAppDetails();
    }

    private void openAppDetails() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void refreshAll() {
        refreshAppButton();
        refreshTimeButton();
        refreshScheduleStatus();
        refreshPermissions();
    }

    private void refreshAppButton() {
        if (appButton == null) {
            return;
        }
        String packageName = preferences.getString(Scheduler.K_PACKAGE, "");
        String label = preferences.getString(Scheduler.K_LABEL, "");
        if (packageName == null || packageName.trim().isEmpty()) {
            appButton.setText("选择第三方 App");
        } else {
            appButton.setText("已选择：" + label + "\n" + packageName);
        }
    }

    private void refreshTimeButton() {
        if (timeButton != null) {
            timeButton.setText(String.format(Locale.getDefault(), "基准时间：%02d:%02d", baseHour, baseMinute));
        }
    }

    private void refreshScheduleStatus() {
        if (scheduleStatus == null) {
            return;
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        long next = preferences.getLong(Scheduler.K_NEXT, 0L);
        long last = preferences.getLong(Scheduler.K_LAST, 0L);
        String nextText = next > 0L ? format.format(new Date(next)) : "无";
        String lastText = last > 0L ? format.format(new Date(last)) : "无";
        scheduleStatus.setText("下一次计划：" + nextText + "\n上一次触发：" + lastText);
    }

    private void refreshPermissions() {
        if (permissionStatus == null) {
            return;
        }
        boolean notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText(
                "通知权限：" + mark(notificationGranted)
                        + "\n精确闹钟：" + mark(Scheduler.canScheduleExact(this))
                        + "\n忽略电池优化：" + mark(isIgnoringBatteryOptimizations())
                        + "\nrealme 自启动/后台活动：需要在厂商设置中手动确认"
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION) {
            refreshPermissions();
            if (permissionFlowActive) {
                continuePermissionFlow();
            } else {
                showFirstRunExplanation();
            }
        }
    }

    private String mark(boolean allowed) {
        return allowed ? "✓ 已允许" : "✕ 未允许";
    }

    private void addSection(LinearLayout root, String title) {
        TextView view = text(title, 20, Color.rgb(23, 32, 51));
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(18), 0, dp(6));
        root.addView(view);
    }

    private LinearLayout labeledInput(String label, EditText input) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView labelView = text(label, 15, Color.rgb(23, 32, 51));
        row.addView(labelView, weighted(2f));
        row.addView(input, weighted(1f));
        return row;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(15f);
        return button;
    }

    private EditText numberInput(String value) {
        EditText editText = new EditText(this);
        editText.setText(value);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        editText.setGravity(Gravity.CENTER);
        editText.setSelectAllOnFocus(true);
        return editText;
    }

    private LinearLayout.LayoutParams fullWidth() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(7);
        return params;
    }

    private LinearLayout.LayoutParams weighted(float weight) {
        return new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                weight
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static final class AppEntry {
        final String label;
        final String packageName;

        AppEntry(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }
}
