package com.iris.assistant;
import android.content.Context;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Local crash metadata; no audio, transcripts, automatic uploads or exception messages. */
final class CrashDiagnostics {
    private static boolean installed;
    private static File file(Context c){return new File(c.getNoBackupFilesDir(),"last-voice-crash.txt");}
    static synchronized void install(Context context){
        if(installed)return;installed=true;Context app=context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        final String version=version(app);
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            try(FileOutputStream out=new FileOutputStream(file(app))){
                String report="IRIS "+version+"; time="+System.currentTimeMillis()+"; thread="+thread.getName()+"\n"+CrashSummary.describe(error)+"\n"+VoiceHealth.snapshot();
                byte[] bytes=report.getBytes(StandardCharsets.UTF_8);out.write(bytes,0,Math.min(bytes.length,32000));
            }catch(Throwable ignored){}
            // Preserve Android's normal crash handling; never attempt to keep a broken process alive.
            if(previous!=null)previous.uncaughtException(thread,error);
            else{android.os.Process.killProcess(android.os.Process.myPid());System.exit(10);}
        });
    }
    private static String version(Context context){try{return context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}catch(Exception error){return "unknown";}}
    static String report(Context context){
        StringBuilder out=new StringBuilder("IRIS crash / wake report\nVersion: ").append(version(context))
            .append("\nDevice: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("; Android ").append(Build.VERSION.RELEASE).append("; SDK ").append(Build.VERSION.SDK_INT).append('\n');
        try{File saved=file(context);if(saved.isFile()&&saved.length()<=32000)try(FileInputStream in=new FileInputStream(saved)){byte[] b=new byte[(int)saved.length()];int n=0,k;while(n<b.length&&(k=in.read(b,n,b.length-n))>0)n+=k;out.append("\nLast recorded Java crash:\n").append(new String(b,0,n,StandardCharsets.UTF_8));}
            else out.append("\nNo Java crash recorded by this build.\n");}catch(Exception e){out.append("\nCrash file unavailable.\n");}
        if(Build.VERSION.SDK_INT>=30){
            try{ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
                for(ApplicationExitInfo exit:manager.getHistoricalProcessExitReasons(context.getPackageName(),0,5)){
                    out.append("\nAndroid process exit: time=").append(exit.getTimestamp()).append("; reason=").append(reason(exit.getReason()))
                        .append("; status=").append(exit.getStatus()).append("; RSS KB=").append(exit.getRss());
                }
            }catch(Exception e){out.append("\nAndroid exit history unavailable.");}
        }else out.append("\nAndroid exit history requires Android 11 or later.");
        out.append("\n\nCurrent-process wake metadata:\n").append(VoiceHealth.snapshot());
        return out.toString();
    }
    private static String reason(int reason){switch(reason){
        case ApplicationExitInfo.REASON_ANR:return "ANR / unresponsive";
        case ApplicationExitInfo.REASON_CRASH:return "Java crash";
        case ApplicationExitInfo.REASON_CRASH_NATIVE:return "Native crash";
        case ApplicationExitInfo.REASON_LOW_MEMORY:return "System low-memory kill";
        case ApplicationExitInfo.REASON_USER_REQUESTED:return "User / system stop";
        default:return Integer.toString(reason);
    }}
}
