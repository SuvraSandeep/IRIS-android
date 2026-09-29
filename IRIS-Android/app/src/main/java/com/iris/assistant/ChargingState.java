package com.iris.assistant;

/** Android battery status values; pure formatting keeps unknown distinct from unplugged. */
final class ChargingState {
    static String describe(int status,int plugged,int level,int scale) {
        String pct=level>=0&&scale>0?" Battery is at "+Math.round(level*100f/scale)+" percent.":"";
        if(status==2)return "Yes, your phone is charging"+(plugged==2?" over USB":plugged==4?" wirelessly":"")+"."+pct;
        if(status==5)return (plugged>0?"Your phone is plugged in and fully charged.":"Your battery is fully charged.")+pct;
        if(plugged>0)return "Your phone is plugged in, but it is not charging right now."+pct;
        if(status==3||status==4||plugged==0)return "Your phone is not charging."+pct;
        return "Android isn't reporting the charging state right now."+pct;
    }
}
