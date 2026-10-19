package com.randomtrigger.launcher;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PlanEditorActivity extends AppCompatActivity {
    public static final String EXTRA_PLAN_ID="edit_plan_id";
    private PlanStore.Plan plan;
    private EditText titleInput,minimumInput,maximumInput;
    private Button appButton,timeButton;
    private final CheckBox[] weekdays=new CheckBox[7];
    private CheckBox enabled,directLocked;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        int id=getIntent().getIntExtra(EXTRA_PLAN_ID,0);
        plan=id>0?PlanStore.find(this,id):new PlanStore.Plan();
        if(plan==null) {Toast.makeText(this,"计划不存在",Toast.LENGTH_LONG).show();finish();return;}
        setContentView(screen());
    }

    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout.LayoutParams margin(){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=dp(7);return p;
    }
    private TextView text(String t,int size) {
        TextView v=new TextView(this);v.setText(t);v.setTextSize(size);
        v.setTextColor(Color.rgb(25,42,64));return v;
    }
    private Button button(String t){
        Button b=new Button(this);b.setText(t);b.setTextSize(15);b.setAllCaps(false);return b;
    }
    private EditText entry(String initial,String hint,boolean number){
        EditText e=new EditText(this);
        e.setSingleLine(true);e.setText(initial);e.setHint(hint);
        if(number) e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        return e;
    }
    private void label(LinearLayout layout,String title) {
        TextView v=text(title,16);v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0,dp(15),0,0);
        layout.addView(v);
    }

    private ScrollView screen(){
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=col();
        root.setPadding(dp(18),dp(20),dp(18),dp(30));
        root.setBackgroundColor(Color.rgb(245,250,255));
        scroll.addView(root);

        TextView header=text(plan.id>0?"编辑计划 #"+plan.id:"新建随机计划",25);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(header);
        root.addView(text("每条计划独立保存、独立触发，其他计划不会被重新随机。",14));

        label(root,"计划名称");
        titleInput=entry(plan.title,"例如：早间提醒、下午提醒",false);
        root.addView(titleInput,margin());

        label(root,"目标 App");
        appButton=button("");
        updateAppButton();
        appButton.setOnClickListener(v->selectApp());
        root.addView(appButton,margin());

        label(root,"触发基准时间");
        timeButton=button("");
        updateTimeButton();
        timeButton.setOnClickListener(v->new TimePickerDialog(this,
                (picker,h,m)->{plan.hour=h;plan.minute=m;updateTimeButton();},
                plan.hour,plan.minute,true).show());
        root.addView(timeButton,margin());
        root.addView(text("系统每天会在基准时间加上随机延迟后，触发一次。",13));

        label(root,"随机延迟（分钟）");
        LinearLayout ranges=row();
        minimumInput=entry(String.valueOf(plan.minDelay),"最小",true);
        maximumInput=entry(String.valueOf(plan.maxDelay),"最大",true);
        ranges.addView(minimumInput,new LinearLayout.LayoutParams(0,-2,1));
        ranges.addView(text("  ～  ",18));
        ranges.addView(maximumInput,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(ranges,margin());
        root.addView(text("例如 08:30 + 0～25 分钟，范围约 08:30～08:56。",13));

        label(root,"星期");
        String[] names={"周一","周二","周三","周四","周五","周六","周日"};
        for(int row=0;row<2;row++){
            LinearLayout days=row();
            for(int col=0;col<4;col++){
                int i=row*4+col;
                if(i>=7) break;
                CheckBox cb=new CheckBox(this);cb.setText(names[i]);cb.setTextSize(13);
                cb.setChecked((plan.weekdays & (1<<i))!=0);
                weekdays[i]=cb;
                days.addView(cb,new LinearLayout.LayoutParams(0,-2,1));
            }
            if(row==1) days.addView(new TextView(this),new LinearLayout.LayoutParams(0,-2,1));
            root.addView(days);
        }

        enabled=new CheckBox(this);enabled.setText("启用此计划");
        enabled.setChecked(plan.enabled);root.addView(enabled,margin());
        directLocked=new CheckBox(this);
        directLocked.setText("锁屏时尝试直接启动目标 App，受系统限制时通知兜底");
        directLocked.setChecked(plan.directWhenLocked);
        root.addView(directLocked,margin());

        Button save=button("保存计划");
        save.setOnClickListener(v->save());root.addView(save,margin());
        Button cancel=button("返回列表");
        cancel.setOnClickListener(v->finish());root.addView(cancel,margin());
        return scroll;
    }

    private void updateAppButton(){
        String label=plan.appLabel==null||plan.appLabel.isEmpty()?"选择第三方 App":plan.appLabel;
        appButton.setText(label+"\n"+(plan.packageName==null?"":plan.packageName));
    }
    private void updateTimeButton(){
        timeButton.setText(String.format(Locale.CHINA,"%02d:%02d",plan.hour,plan.minute));
    }

    private void selectApp(){
        Intent i=new Intent(Intent.ACTION_MAIN);i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found=getPackageManager().queryIntentActivities(i,PackageManager.MATCH_ALL);
        Map<String,String> packages=new LinkedHashMap<>();
        for(ResolveInfo info:found) {
            if(info.activityInfo==null) continue;
            String pkg=info.activityInfo.packageName;
            if(pkg.equals(getPackageName())) continue;
            packages.put(pkg,String.valueOf(info.loadLabel(getPackageManager())));
        }
        ArrayList<Map.Entry<String,String>> list=new ArrayList<>(packages.entrySet());
        Collections.sort(list,(a,b)->a.getValue().compareToIgnoreCase(b.getValue()));
        if(list.isEmpty()) {Toast.makeText(this,"没有可选择的 App",Toast.LENGTH_LONG).show();return;}
        String[] labels=new String[list.size()];
        for(int n=0;n<list.size();n++){
            Map.Entry<String,String> app=list.get(n);
            labels[n]=app.getValue()+"\n"+app.getKey();
        }
        new AlertDialog.Builder(this).setTitle("选择目标 App")
            .setItems(labels,(d,selected)->{
                Map.Entry<String,String> app=list.get(selected);
                plan.packageName=app.getKey();plan.appLabel=app.getValue();updateAppButton();
            }).setNegativeButton("取消",null).show();
    }

    private void save(){
        String name=titleInput.getText().toString().trim();
        if(name.isEmpty()) {titleInput.setError("请输入名称");return;}
        if(name.length()>50) {titleInput.setError("名称请控制在50字以内");return;}
        plan.title=name;
        try {
            plan.minDelay=Integer.parseInt(minimumInput.getText().toString().trim());
            plan.maxDelay=Integer.parseInt(maximumInput.getText().toString().trim());
        } catch(Exception e){
            Toast.makeText(this,"请输入数字形式的分钟",Toast.LENGTH_LONG).show();return;
        }
        plan.weekdays=0;
        for(int n=0;n<weekdays.length;n++) if(weekdays[n].isChecked()) plan.weekdays|=1<<n;
        plan.enabled=enabled.isChecked();
        plan.directWhenLocked=directLocked.isChecked();
        if(!plan.valid()){
            Toast.makeText(this,"请检查目标 App、时间、随机范围(0～1440)和星期",Toast.LENGTH_LONG).show();
            return;
        }
        int oldId=plan.id;
        try {
            if(oldId>0) PlanScheduler.cancel(this,oldId);
            plan.nextAt=0L;plan.nextWindowDay=0L;
            PlanStore.Plan saved=PlanStore.save(this,plan);
            if(saved.enabled) PlanScheduler.schedule(this,saved.id,true);
            Toast.makeText(this,"计划已保存",Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);finish();
        } catch(Exception e) {
            if(oldId>0) try{PlanScheduler.schedule(this,oldId,false);}catch(Exception ignored){}
            Toast.makeText(this,"保存失败："+e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }
}
