package com.iris.assistant;
public final class IrisApplication extends android.app.Application {
    @Override public void onCreate(){
        super.onCreate();
        CrashDiagnostics.install(this);
        try { new ProfileStore(this).seedDefaultWakePhrase(); } catch (Throwable ignored) { }
    }
}
