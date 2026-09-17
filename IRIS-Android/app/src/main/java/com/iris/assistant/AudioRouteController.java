package com.iris.assistant;

import android.content.Context;
import android.media.*;
import android.os.Build;

/** Owns a recorder route. A connected headset is not proof of the recording input. */
final class AudioRouteController implements AutoCloseable {
    /** Which physical route a confirmed recording actually used. Wired/USB/Bluetooth are all
     *  grouped as HEADSET: they share the same reason a separate profile matters — the mic
     *  capsule (and often its distance/angle from the mouth) differs from the phone's built-in
     *  mic, not any distinction the app needs to make between headset types. */
    enum Route {PHONE,HEADSET,UNCONFIRMED}
    static volatile String observed="Microphone idle";
    static volatile Route observedRoute=Route.UNCONFIRMED;
    private final AudioManager manager;
    private final int previousMode;
    private boolean changed;
    AudioRouteController(Context context){
        manager=context==null?null:(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        previousMode=manager==null?AudioManager.MODE_NORMAL:manager.getMode();
    }
    void request(Context context,AudioRecord recorder){
        observed="Microphone route unconfirmed";
        if(manager==null)return;
        if(previousMode==AudioManager.MODE_IN_CALL||previousMode==AudioManager.MODE_IN_COMMUNICATION)return;
        try{
            String preference=new AppSettings(context).preferredMicrophone();
            AudioDeviceInfo chosen=null;
            for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_INPUTS)){
                boolean bt=d.getType()==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||(Build.VERSION.SDK_INT>=31&&d.getType()==AudioDeviceInfo.TYPE_BLE_HEADSET);
                boolean phone=d.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC;
                boolean wired=d.getType()==AudioDeviceInfo.TYPE_WIRED_HEADSET||d.getType()==AudioDeviceInfo.TYPE_USB_HEADSET||d.getType()==AudioDeviceInfo.TYPE_USB_DEVICE;
                if((preference.equals("Phone")&&phone)||(preference.equals("Bluetooth")&&bt)||(preference.equals("Wired / USB")&&wired)){chosen=d;break;}
                if(preference.equals("Automatic") && (chosen==null||wired||bt))chosen=d;
            }
            if(chosen!=null){
                boolean bt=chosen.getType()==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||(Build.VERSION.SDK_INT>=31&&chosen.getType()==AudioDeviceInfo.TYPE_BLE_HEADSET);
                if(bt){
                    manager.setMode(AudioManager.MODE_IN_COMMUNICATION);changed=true;
                    observed="Bluetooth requested; input unconfirmed";
                    if(Build.VERSION.SDK_INT>=31){
                        for(AudioDeviceInfo d:manager.getAvailableCommunicationDevices())if(d.getType()==chosen.getType()){manager.setCommunicationDevice(d);break;}
                    }else{manager.startBluetoothSco();manager.setBluetoothScoOn(true);}
                }
                recorder.setPreferredDevice(chosen);
            }
            recorder.addOnRoutingChangedListener(r->observe(recorder),null);
        }catch(Exception error){observed="Requested microphone unavailable; checking actual input";}
    }
    static int routeId(AudioRecord recorder){try{AudioDeviceInfo d=recorder.getRoutedDevice();return d==null?-1:d.getId();}catch(Exception e){return -1;}}
    /** What's actually plugged in / connected right now, independent of any active recording —
     *  observed/observedRoute only update DURING a take and reset to idle the instant it ends,
     *  so between takes (exactly when a user deciding whether to connect/disconnect a headset
     *  needs to know) the UI previously showed "Microphone idle" instead of anything actionable.
     *  This lets the training UI show the current hardware state continuously, and proactively
     *  warn about a route mismatch BEFORE the next take is recorded and rejected, rather than
     *  only after. Returns HEADSET if any Bluetooth/wired/USB input is currently connected
     *  (whether or not it will end up being the one actually used), PHONE otherwise. */
    static Route currentlyConnected(Context context){
        if(context==null)return Route.UNCONFIRMED;
        try{
            AudioManager manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            if(manager==null)return Route.UNCONFIRMED;
            for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_INPUTS)){
                boolean bt=d.getType()==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||(Build.VERSION.SDK_INT>=31&&d.getType()==AudioDeviceInfo.TYPE_BLE_HEADSET);
                boolean wired=d.getType()==AudioDeviceInfo.TYPE_WIRED_HEADSET||d.getType()==AudioDeviceInfo.TYPE_USB_HEADSET||d.getType()==AudioDeviceInfo.TYPE_USB_DEVICE;
                if(bt||wired)return Route.HEADSET;
            }
            return Route.PHONE;
        }catch(Exception error){return Route.UNCONFIRMED;}
    }
    static void observe(AudioRecord recorder){
        try{AudioDeviceInfo actual=recorder.getRoutedDevice();
            if(actual==null){observed="Recording input unconfirmed";observedRoute=Route.UNCONFIRMED;return;}
            boolean phone=actual.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC;
            observed=(phone?"Phone microphone":String.valueOf(actual.getProductName()))+" — confirmed input";
            observedRoute=phone?Route.PHONE:Route.HEADSET;
            IrisListeningService.currentMic=observed;
        }catch(Exception ignored){observed="Recording input unconfirmed";observedRoute=Route.UNCONFIRMED;}
    }
    public void close(){
        if(changed&&manager!=null)try{if(Build.VERSION.SDK_INT>=31)manager.clearCommunicationDevice();else{manager.stopBluetoothSco();manager.setBluetoothScoOn(false);}manager.setMode(previousMode);}catch(Exception ignored){}
        observed="Microphone idle";observedRoute=Route.UNCONFIRMED;
    }
}
