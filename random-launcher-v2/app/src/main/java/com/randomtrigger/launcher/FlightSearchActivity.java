package com.randomtrigger.launcher;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Actual flight lookup: builds a Google Flights query and displays the provider
 * site in a WebView. Ctrip's official mobile search page is provided separately
 * because its private results API should not be spoofed or scraped.
 */
public final class FlightSearchActivity extends AppCompatActivity {
    private static final String CTRIP_URL="https://m.ctrip.com/html5/flight/";
    private EditText origin,destination;
    private Button departButton,returnButton,externalButton;
    private CheckBox roundTrip;
    private Spinner adults;
    private WebView browser;
    private TextView status;
    private ProgressBar progress;
    private LocalDate departure=LocalDate.now().plusDays(1);
    private LocalDate returning=LocalDate.now().plusDays(8);
    private String currentUrl=CTRIP_URL;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        setContentView(buildScreen());
        open(CTRIP_URL);
    }

    private LinearLayout col(){LinearLayout x=new LinearLayout(this);x.setOrientation(1);return x;}
    private LinearLayout row(){LinearLayout x=new LinearLayout(this);x.setOrientation(0);return x;}
    private LinearLayout.LayoutParams full(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(7);return p;}
    private TextView text(String value,int sp){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(Color.rgb(22,49,76));return t;}
    private Button button(String value){Button b=new Button(this);b.setAllCaps(false);b.setText(value);b.setTextSize(13);return b;}
    private EditText input(String hint,String def){EditText e=new EditText(this);e.setHint(hint);e.setSingleLine(true);e.setText(def);return e;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout.LayoutParams weighted(int w){return new LinearLayout.LayoutParams(0,-2,w);}

    private View buildScreen(){
        LinearLayout outer=col();outer.setBackgroundColor(Color.rgb(245,250,255));
        ScrollView formScroll=new ScrollView(this);
        LinearLayout form=col();form.setPadding(dp(15),dp(12),dp(15),dp(8));
        formScroll.addView(form);

        LinearLayout top=row();
        Button back=button("← 随机计划");
        back.setOnClickListener(v->finish());
        top.addView(back,weighted(1));
        TextView heading=text("机票查询 · Flight Search",20);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(heading,weighted(2));
        form.addView(top,full());

        TextView info=text("实时航班和票价来自第三方网站；本页面不收取费用，也不虚构报价。",13);
        form.addView(info,full());

        LinearLayout stations=row();
        origin=input("出发地 / IATA","北京");
        destination=input("目的地 / IATA","上海");
        stations.addView(origin,weighted(1));
        stations.addView(destination,weighted(1));
        form.addView(stations,full());

        LinearLayout dates=row();
        departButton=button("");
        departButton.setOnClickListener(v->datePicker(false));
        returnButton=button("");
        returnButton.setOnClickListener(v->datePicker(true));
        dates.addView(departButton,weighted(1));
        dates.addView(returnButton,weighted(1));
        form.addView(dates,full());

        LinearLayout switches=row();
        roundTrip=new CheckBox(this);roundTrip.setText("往返");
        roundTrip.setChecked(false);
        roundTrip.setOnCheckedChangeListener((buttonView,checked)->updateDates());
        switches.addView(roundTrip,weighted(1));
        adults=new Spinner(this);
        String[] counts={"1位成人","2位成人","3位成人","4位成人","5位成人","6位成人"};
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,counts);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        adults.setAdapter(adapter);
        switches.addView(adults,weighted(1));
        form.addView(switches,full());
        updateDates();

        LinearLayout searches=row();
        Button google=button("按条件搜索航班");
        google.setOnClickListener(v->searchGoogle());
        Button ctrip=button("携程机票");
        ctrip.setOnClickListener(v->{
            Toast.makeText(this,"携程页面需在网页内选择城市及日期",Toast.LENGTH_LONG).show();
            open(CTRIP_URL);
        });
        searches.addView(google,weighted(1));searches.addView(ctrip,weighted(1));
        form.addView(searches,full());

        LinearLayout toolbar=row();
        externalButton=button("用浏览器打开");
        externalButton.setOnClickListener(v->openExternal());
        Button reload=button("刷新");
        reload.setOnClickListener(v->browser.reload());
        toolbar.addView(externalButton,weighted(1));toolbar.addView(reload,weighted(1));
        form.addView(toolbar,full());
        status=text("正在连接航班搜索网站……",13);
        form.addView(status,full());
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);progress.setVisibility(View.GONE);
        form.addView(progress,full());
        outer.addView(formScroll,new LinearLayout.LayoutParams(-1,-2));

        browser=new WebView(this);
        WebSettings settings=browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        browser.setWebChromeClient(new WebChromeClient(){
            @Override public void onProgressChanged(WebView view,int newProgress) {
                progress.setVisibility(newProgress>=100?View.GONE:View.VISIBLE);
                progress.setProgress(newProgress);
            }
        });
        browser.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) {
                Uri url=request.getUrl();
                String scheme=url.getScheme();
                if("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme)) {
                    currentUrl=url.toString();
                    return false;
                }
                try{startActivity(new Intent(Intent.ACTION_VIEW,url));}catch(Exception ignored){}
                return true;
            }
            @Override public void onPageFinished(WebView view,String url){
                status.setText("当前服务："+(url.contains("ctrip")?"携程":url.contains("google")?"Google Flights":"第三方航班网站")
                    +"  ·  票价以网站显示为准");
                currentUrl=url;
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){
                if(request.isForMainFrame()) status.setText(
                        "航班服务打开失败。可以点击「用浏览器打开」，或切换另一航班服务。");
            }
        });
        outer.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        return outer;
    }

    private void updateDates(){
        if(departButton==null)return;
        departButton.setText("出发："+departure);
        boolean isRound=roundTrip!=null && roundTrip.isChecked();
        returnButton.setEnabled(isRound);
        returnButton.setText(isRound?"返程："+returning:"单程");
    }

    private void datePicker(boolean isReturn){
        LocalDate selected=isReturn?returning:departure;
        DatePickerDialog dlg=new DatePickerDialog(this,(view,year,month,day)->{
            LocalDate picked=LocalDate.of(year,month+1,day);
            if(isReturn) returning=picked; else departure=picked;
            updateDates();
        },selected.getYear(),selected.getMonthValue()-1,selected.getDayOfMonth());
        dlg.getDatePicker().setMinDate(System.currentTimeMillis()-60000L);
        dlg.show();
    }

    private void searchGoogle(){
        String from=origin.getText().toString().trim();
        String to=destination.getText().toString().trim();
        if(from.isEmpty()){origin.setError("请填写出发地");return;}
        if(to.isEmpty()){destination.setError("请填写目的地");return;}
        if(from.equalsIgnoreCase(to)){destination.setError("出发地与目的地不能相同");return;}
        if(departure.isBefore(LocalDate.now())){Toast.makeText(this,"出发日期不能早于今天",Toast.LENGTH_LONG).show();return;}
        boolean isRound=roundTrip.isChecked();
        if(isRound && returning.isBefore(departure)){
            Toast.makeText(this,"返程日期不能早于出发日期",Toast.LENGTH_LONG).show();return;
        }
        StringBuilder query=new StringBuilder();
        query.append("Flights from ").append(from).append(" to ").append(to)
            .append(" on ").append(departure);
        if(isRound) query.append(" returning ").append(returning);
        else query.append(" one way");
        query.append(" ").append(adults.getSelectedItemPosition()+1).append(" adult");
        if(adults.getSelectedItemPosition()>0) query.append("s");
        String url=new Uri.Builder().scheme("https").authority("www.google.com")
                .appendPath("travel").appendPath("flights")
                .appendQueryParameter("q",query.toString())
                .appendQueryParameter("hl","zh-CN").build().toString();
        open(url);
    }

    private void open(String url){
        currentUrl=url;
        status.setText("正在查询实时航班……");
        browser.loadUrl(url);
    }

    private void openExternal(){
        try{
            startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(currentUrl)));
        } catch(Exception e){
            Toast.makeText(this,"没有可用浏览器",Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onBackPressed(){
        if(browser!=null&&browser.canGoBack())browser.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy(){
        if(browser!=null){
            browser.stopLoading();
            browser.setWebChromeClient(null);
            browser.setWebViewClient(null);
            browser.destroy();
        }
        super.onDestroy();
    }
}
