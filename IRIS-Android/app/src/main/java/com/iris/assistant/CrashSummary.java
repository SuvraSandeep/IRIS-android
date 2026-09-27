package com.iris.assistant;
/** Stack locations only: exception messages can contain dictated text or personal data. */
final class CrashSummary {
    static String describe(Throwable error){
        StringBuilder text=new StringBuilder();
        for(int cause=0;error!=null&&cause<3;cause++,error=error.getCause()){
            text.append(error.getClass().getName()).append('\n');
            StackTraceElement[] frames=error.getStackTrace();
            for(int i=0;i<Math.min(24,frames.length);i++)text.append("  at ").append(frames[i]).append('\n');
        }
        return text.length()>12000?text.substring(0,12000):text.toString();
    }
}
