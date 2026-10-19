package com.randomtrigger.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Persisted per-plan data; storage is available before first unlock after reboot. */
public final class PlanStore {
    private static final String STORE = "flight_search_plan_store_v3";
    private static final String PLANS = "plans_json";
    private static final String NEXT_ID = "next_plan_id";
    private static final String MIGRATED = "migration_complete";
    private static final Object MUTEX = new Object();

    private PlanStore() {}

    public static final class Plan {
        public int id;
        public String title = "";
        public String packageName = "";
        public String appLabel = "";
        public int hour = 8;
        public int minute = 30;
        public int minDelay = 0;
        public int maxDelay = 30;
        public int weekdays = 31; // Monday bit0 ... Sunday bit6
        public boolean enabled = true;
        public boolean directWhenLocked = true;
        public long nextAt;
        public long lastAt;

        public Plan copy() {
            Plan p = new Plan();
            p.id=id; p.title=title; p.packageName=packageName; p.appLabel=appLabel;
            p.hour=hour; p.minute=minute; p.minDelay=minDelay; p.maxDelay=maxDelay;
            p.weekdays=weekdays; p.enabled=enabled; p.directWhenLocked=directWhenLocked;
            p.nextAt=nextAt; p.lastAt=lastAt;
            return p;
        }

        public String name() {
            return title == null || title.trim().isEmpty() ? "计划 " + id : title.trim();
        }

        public boolean valid() {
            return packageName != null && !packageName.trim().isEmpty()
                && hour>=0 && hour<=23 && minute>=0 && minute<=59
                && minDelay>=0 && maxDelay>=minDelay && maxDelay<=1440 && weekdays!=0;
        }

        JSONObject json() throws Exception {
            JSONObject j = new JSONObject();
            j.put("id",id); j.put("title",title); j.put("pkg",packageName);
            j.put("label",appLabel); j.put("hour",hour); j.put("minute",minute);
            j.put("min",minDelay); j.put("max",maxDelay); j.put("days",weekdays);
            j.put("enabled",enabled); j.put("locked",directWhenLocked);
            j.put("next",nextAt); j.put("last",lastAt);
            return j;
        }

        static Plan from(JSONObject j) {
            Plan p=new Plan();
            p.id=j.optInt("id"); p.title=j.optString("title","");
            p.packageName=j.optString("pkg",""); p.appLabel=j.optString("label","");
            p.hour=j.optInt("hour",8); p.minute=j.optInt("minute",30);
            p.minDelay=j.optInt("min",0); p.maxDelay=j.optInt("max",30);
            p.weekdays=j.optInt("days",31); p.enabled=j.optBoolean("enabled",true);
            p.directWhenLocked=j.optBoolean("locked",true);
            p.nextAt=j.optLong("next"); p.lastAt=j.optLong("last");
            return p;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return Scheduler.storage(c).getSharedPreferences(STORE,Context.MODE_PRIVATE);
    }

    private static List<Plan> raw(SharedPreferences prefs) {
        List<Plan> result = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(PLANS,"[]"));
            for(int i=0;i<a.length();i++) {
                JSONObject j = a.optJSONObject(i);
                if(j!=null) {
                    Plan p=Plan.from(j);
                    if(p.id>0) result.add(p);
                }
            }
        } catch (Exception e) { throw new IllegalStateException("读取计划数据失败",e); }
        result.sort(Comparator.comparingInt(p->p.id));
        return result;
    }

    private static void write(SharedPreferences store,List<Plan> plans) {
        try {
            JSONArray a=new JSONArray();
            for(Plan p:plans) a.put(p.json());
            if(!store.edit().putString(PLANS,a.toString()).commit()) {
                throw new IllegalStateException("存储设备拒绝写入");
            }
        } catch(Exception e) { throw new IllegalStateException("保存计划失败",e); }
    }

    public static void migrateOldPlan(Context context) {
        synchronized (MUTEX) {
            SharedPreferences s=prefs(context);
            if(s.getBoolean(MIGRATED,false)) return;
            List<Plan> plans=raw(s);
            SharedPreferences old=Scheduler.storage(context)
                .getSharedPreferences("random_launcher_v2",Context.MODE_PRIVATE);
            String pkg=old.getString("target_package","");
            if(plans.isEmpty() && pkg!=null && !pkg.isEmpty()) {
                Plan p=new Plan();
                p.id=1; p.title="原有计划";
                p.packageName=pkg; p.appLabel=old.getString("target_label",pkg);
                p.hour=old.getInt("base_hour",8); p.minute=old.getInt("base_minute",30);
                p.minDelay=old.getInt("random_min_minutes",0);
                p.maxDelay=old.getInt("random_max_minutes",30);
                p.weekdays=old.getInt("week_mask",31);
                p.enabled=old.getBoolean("enabled",false);
                p.directWhenLocked=old.getBoolean("direct_launch_while_locked",true);
                p.lastAt=old.getLong("last_trigger_at",0L);
                p.nextAt=old.getLong("next_trigger_at",0L);
                plans.add(p);
                write(s,plans);
                s.edit().putInt(NEXT_ID,2).commit();
                // Old v2 alarm is not a plan alarm and must not fire alongside migrated one.
                Scheduler.cancelMain(context);
            }
            s.edit().putBoolean(MIGRATED,true).commit();
        }
    }

    public static List<Plan> list(Context c) {
        migrateOldPlan(c);
        synchronized(MUTEX) {
            List<Plan> copies=new ArrayList<>();
            for(Plan p:raw(prefs(c))) copies.add(p.copy());
            return copies;
        }
    }

    public static Plan find(Context c,int id) {
        synchronized(MUTEX) {
            for(Plan p:raw(prefs(c))) if(p.id==id) return p.copy();
        }
        return null;
    }

    public static Plan save(Context c,Plan input) {
        if(!input.valid()) throw new IllegalArgumentException("请填写有效应用、时间区间和星期");
        synchronized(MUTEX) {
            SharedPreferences s=prefs(c);
            List<Plan> plans=raw(s);
            Plan p=input.copy();
            boolean found=false;
            if(p.id>0) {
                for(int i=0;i<plans.size();i++) if(plans.get(i).id==p.id) {
                    p.lastAt=plans.get(i).lastAt;
                    plans.set(i,p); found=true; break;
                }
                if(!found) throw new IllegalArgumentException("编辑的计划已不存在");
            } else {
                int next=Math.max(1,s.getInt(NEXT_ID,1));
                for(Plan existing:plans) next=Math.max(next,existing.id+1);
                p.id=next;
                if(!s.edit().putInt(NEXT_ID,next+1).commit()) throw new IllegalStateException("无法保存编号");
                plans.add(p);
            }
            write(s,plans);
            return p.copy();
        }
    }

    public static boolean delete(Context c,int id) {
        synchronized(MUTEX) {
            SharedPreferences s=prefs(c);
            List<Plan> plans=raw(s);
            boolean removed=plans.removeIf(p->p.id==id);
            if(removed) write(s,plans);
            return removed;
        }
    }

    public static boolean setEnabled(Context c,int id,boolean enabled) {
        synchronized(MUTEX) {
            SharedPreferences s=prefs(c);
            List<Plan> plans=raw(s);
            for(Plan p:plans) if(p.id==id) {
                p.enabled=enabled; p.nextAt=0L;
                write(s,plans);
                return true;
            }
            return false;
        }
    }

    public static boolean setNextTime(Context c,int id,long at) {
        synchronized(MUTEX) {
            SharedPreferences s=prefs(c);
            List<Plan> plans=raw(s);
            for(Plan p:plans) if(p.id==id) {
                p.nextAt=at;
                write(s,plans);
                return true;
            }
            return false;
        }
    }

    /** Ignore stale, cancelled, duplicate or edited-plan alarm deliveries. */
    public static boolean markTriggered(Context c,int id,long expectedAt) {
        synchronized(MUTEX) {
            SharedPreferences s=prefs(c);
            List<Plan> plans=raw(s);
            for(Plan p:plans) if(p.id==id) {
                if(!p.enabled || p.nextAt!=expectedAt || expectedAt<=0L) return false;
                p.nextAt=0L;
                p.lastAt=System.currentTimeMillis();
                write(s,plans);
                return true;
            }
            return false;
        }
    }
}
