package com.randomtrigger.launcher;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/** Real Android 13 test of multi-plan persistence, independent scheduling and edits. */
@RunWith(AndroidJUnit4.class)
public final class MultiPlanTest {
    @Test public void twoIndependentPlansCanBeEditedWithoutTouchingOtherAlarms() {
        Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        PlanStore.Plan morning=new PlanStore.Plan();
        morning.title="上午测试计划"; morning.packageName="com.android.settings";
        morning.appLabel="Settings"; morning.hour=8; morning.minute=25;
        morning.minDelay=0; morning.maxDelay=20; morning.weekdays=127;
        PlanStore.Plan evening=new PlanStore.Plan();
        evening.title="下午测试计划"; evening.packageName="com.android.settings";
        evening.appLabel="Settings"; evening.hour=17; evening.minute=45;
        evening.minDelay=2; evening.maxDelay=35; evening.weekdays=127;

        morning=PlanStore.save(ctx,morning);
        evening=PlanStore.save(ctx,evening);
        int first=morning.id,second=evening.id;
        try {
            assertNotEquals(first,second);
            long mAt=PlanScheduler.schedule(ctx,first,true);
            long eAt=PlanScheduler.schedule(ctx,second,true);
            assertTrue(mAt>System.currentTimeMillis());
            assertTrue(eAt>System.currentTimeMillis());
            assertEquals(2,PlanStore.list(ctx).size());

            PlanStore.Plan changed=PlanStore.find(ctx,first);
            changed.title="编辑后的上午计划";
            changed.minute=30;
            changed.nextAt=0;
            PlanStore.save(ctx,changed);
            PlanScheduler.schedule(ctx,first,true);

            assertEquals("编辑后的上午计划",PlanStore.find(ctx,first).title);
            assertEquals("下午测试计划",PlanStore.find(ctx,second).title);
            assertEquals(eAt,PlanStore.find(ctx,second).nextAt);

            PlanStore.setEnabled(ctx,first,false);
            PlanScheduler.cancel(ctx,first);
            assertFalse(PlanStore.find(ctx,first).enabled);
            assertTrue(PlanStore.find(ctx,second).enabled);
            assertEquals(eAt,PlanStore.find(ctx,second).nextAt);

            long baseDay=PlanStore.find(ctx,second).nextWindowDay;
            assertTrue(PlanStore.markTriggered(ctx,second,eAt));
            assertFalse(PlanStore.markTriggered(ctx,second,eAt));
            PlanScheduler.schedule(ctx,second,true);
            assertTrue(PlanStore.find(ctx,second).nextWindowDay>baseDay);

            PlanScheduler.cancel(ctx,first);
            PlanStore.delete(ctx,first);
            assertNotNull(PlanStore.find(ctx,second));
            assertTrue(PlanStore.find(ctx,second).enabled);
        } finally {
            PlanScheduler.cancel(ctx,first);
            PlanScheduler.cancel(ctx,second);
            PlanStore.delete(ctx,first);
            PlanStore.delete(ctx,second);
        }
    }
}
