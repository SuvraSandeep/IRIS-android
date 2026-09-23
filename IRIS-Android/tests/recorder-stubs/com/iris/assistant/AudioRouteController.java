package com.iris.assistant;
import android.media.AudioRecord;import android.content.Context;
final class AudioRouteController {
 enum Route {PHONE,HEADSET,UNCONFIRMED}
 static Route requested=Route.UNCONFIRMED;
 static volatile String observed="Test microphone";
 static volatile Route observedRoute=Route.UNCONFIRMED;
 static int routeId(AudioRecord r){return 1;}
 AudioRouteController(Context c){}void request(Context c,AudioRecord r){} void request(Context c,AudioRecord r,boolean bt,Route required){requested=required;}
 static void awaitInput(AudioRecord r,Route required){if(AudioRecord.scenario.equals("route-failed"))throw new IllegalStateException("route unavailable");}
 static void observe(AudioRecord r){observedRoute=Route.HEADSET;}
 void close(){observedRoute=Route.UNCONFIRMED;}
}
