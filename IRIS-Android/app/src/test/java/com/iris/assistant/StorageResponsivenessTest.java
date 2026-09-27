package com.iris.assistant;

import android.content.Context;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class StorageResponsivenessTest {
    @Test public void blockedLogStorageDoesNotBlockAppendOrProfileRead() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        new AppSettings(context).setLogMode(AppSettings.LOG_COMMANDS);
        Object logLock=SecureStore.fileLock(context,"iris_activity_v2.enc");
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try {
            synchronized(logLock) {
                caller.submit(()->LogStore.append(context,"TEST","event")).get(1,TimeUnit.SECONDS);
                // Saturation must drop best-effort logs, never move disk work onto the caller.
                caller.submit(()->{for(int i=0;i<300;i++)LogStore.append(context,"TEST","event");}).get(1,TimeUnit.SECONDS);
                assertEquals("missing",caller.submit(()->SecureStore.read(context,"missing-profile.enc","missing")).get(1,TimeUnit.SECONDS));
            }
        } finally { caller.shutdownNow(); }
    }

    @Test public void telemetryRefreshReturnsWhileProfileStorageIsBlocked() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        SystemTelemetryController telemetry=new SystemTelemetryController(context);
        java.lang.reflect.Field running=SystemTelemetryController.class.getDeclaredField("running");
        running.setAccessible(true);running.setBoolean(telemetry,true);
        java.lang.reflect.Method refresh=SystemTelemetryController.class.getDeclaredMethod("rebuild",boolean.class);
        refresh.setAccessible(true);
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try {
            synchronized(SecureStore.fileLock(context,"iris_profile_v2.enc")) {
                caller.submit(()->{try {refresh.invoke(telemetry,true);}catch(Exception e){throw new RuntimeException(e);}}).get(1,TimeUnit.SECONDS);
            }
        } finally { telemetry.close(); caller.shutdownNow(); }
    }

    @Test public void fileLocksSerializeSameFileButIsolateOtherFiles() {
        Context context=RuntimeEnvironment.getApplication();
        assertSame(SecureStore.fileLock(context,"profile"),SecureStore.fileLock(context,"profile"));
        assertNotSame(SecureStore.fileLock(context,"profile"),SecureStore.fileLock(context,"log"));
    }
}
