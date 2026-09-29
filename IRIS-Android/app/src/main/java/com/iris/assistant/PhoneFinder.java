package com.iris.assistant;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;

/** Bounded alarm playback; never claims to override Android total-silence policies. */
final class PhoneFinder {
    private static final int ID=0x1818;
    private final Context context;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private AudioManager audio;
    private int previous=-1,raised=-1;
    private Runnable finished;
    private final Runnable timeout=()->{Runnable done=finished;stop();if(done!=null)done.run();};
    PhoneFinder(Context context){this.context=context;}
    boolean active(){return player!=null;}
    String start(Runnable done){
        stop();finished=done;
        try{
            NotificationManager nm=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
            int filter=nm.getCurrentInterruptionFilter();
            if(filter==NotificationManager.INTERRUPTION_FILTER_NONE
                ||(filter==NotificationManager.INTERRUPTION_FILTER_PRIORITY
                  &&((Build.VERSION.SDK_INT>=30?nm.getConsolidatedNotificationPolicy():nm.getNotificationPolicy()).priorityCategories&NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS)==0)){
                stop();return "Do Not Disturb is blocking alarms. Open IRIS Settings, Find my phone, and allow alarms in Android's active Do Not Disturb mode.";
            }
            audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            previous=audio.getStreamVolume(AudioManager.STREAM_ALARM);
            raised=audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            audio.setStreamVolume(AudioManager.STREAM_ALARM,raised,0);
            // Save the actual applied value; fixed-volume devices may ignore the request.
            raised=audio.getStreamVolume(AudioManager.STREAM_ALARM);
            player=new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            android.net.Uri sound=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if(sound==null)sound=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            player.setDataSource(context,sound);player.setLooping(true);
            if(Build.VERSION.SDK_INT>=23)for(AudioDeviceInfo device:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                if(device.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER){player.setPreferredDevice(device);break;}
            player.setOnErrorListener((mp,what,extra)->{LogStore.append(context,"FIND_PHONE","Playback failed: "+what);timeout.run();return true;});
            player.prepare();player.start();
            NotificationChannel channel=new NotificationChannel("iris_finder","Find my phone",NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null,null);nm.createNotificationChannel(channel);
            PendingIntent stop=PendingIntent.getService(context,ID,new Intent(context,IrisListeningService.class).setAction(IrisListeningService.ACTION_STOP_FINDER),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            nm.notify(ID,new Notification.Builder(context,"iris_finder").setSmallIcon(R.drawable.ic_iris)
                .setContentTitle("IRIS · Here I am").setContentText("Alarm stops after 30 seconds. Tap Stop when found.")
                .addAction(new Notification.Action.Builder(null,"Stop",stop).build()).setContentIntent(stop).setOngoing(true).build());
            handler.postDelayed(timeout,30000);
            return "Here I am. Alarm started for 30 seconds; use the Stop notification when you find me.";
        }catch(Exception error){stop();LogStore.append(context,"FIND_PHONE",CrashSummary.describe(error));return "I couldn't start the phone-finding alarm. Check alarm volume and Do Not Disturb in IRIS Settings.";}
    }
    void stop(){
        handler.removeCallbacks(timeout);finished=null;
        if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}
        try{if(audio!=null&&previous>=0&&audio.getStreamVolume(AudioManager.STREAM_ALARM)==raised)audio.setStreamVolume(AudioManager.STREAM_ALARM,previous,0);}catch(Exception ignored){}
        previous=raised=-1;
        try{((NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(ID);}catch(Exception ignored){}
    }
}
