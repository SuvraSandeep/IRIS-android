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
    private CommunicationRouteLease communication;
    private AudioDeviceInfo bluetoothOutput;
    private boolean media,listenerAdded;
    private Route requiredRoute=Route.UNCONFIRMED;
    AudioRouteController(Context context){manager=context==null?null:(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);}
    void request(Context context,AudioRecord recorder){request(context,recorder,true);}
    void request(Context context,AudioRecord recorder,boolean allowBluetooth){request(context,recorder,allowBluetooth,Route.UNCONFIRMED);}
    void request(Context context,AudioRecord recorder,boolean allowBluetooth,Route required){
        requiredRoute=required;observed="Microphone route unconfirmed";observedRoute=Route.UNCONFIRMED;
        if(manager==null)return;
        media=manager.isMusicActive();
        // Explicit headset training may request its mic briefly. Passive listening preserves
        // media output and uses the phone mic when classic Bluetooth needs a call-mode switch.
        boolean bluetoothAllowed=allowBluetooth&&(required==Route.HEADSET||!media);
        try{
            if(manager.getMode()!=AudioManager.MODE_NORMAL&&(communication==null||!communication.owned()))return;
            String preference=required==Route.PHONE?"Phone":required==Route.HEADSET?"Automatic":new AppSettings(context).preferredMicrophone();
            AudioDeviceInfo chosen=null;int best=-1;
            for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_INPUTS)){
                boolean bt=bluetooth(d),phone=d.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC;
                boolean wired=d.getType()==AudioDeviceInfo.TYPE_WIRED_HEADSET||d.getType()==AudioDeviceInfo.TYPE_USB_HEADSET||d.getType()==AudioDeviceInfo.TYPE_USB_DEVICE;
                if(!bt&&!phone&&!wired)continue;
                if(required==Route.HEADSET&&!bt&&!wired)continue;
                if(bt&&!bluetoothAllowed)continue;
                int score=phone?1:bt?2:3;
                if(preference.equals("Phone")){if(!phone)continue;score=10;}
                else if(preference.equals("Bluetooth")&&bt)score=10;
                else if(preference.equals("Wired / USB")&&wired)score=10;
                if(score>best){chosen=d;best=score;}
            }
            if(chosen!=null&&bluetooth(chosen)){
                bluetoothOutput=null;
                if(Build.VERSION.SDK_INT>=31){
                    for(AudioDeviceInfo d:manager.getAvailableCommunicationDevices())if(bluetooth(d)&&d.getType()==chosen.getType()){
                        bluetoothOutput=d;if(d.getProductName().toString().equals(chosen.getProductName().toString()))break;
                    }
                    if(bluetoothOutput==null)throw new IllegalStateException("Bluetooth communication output unavailable");
                }
                if(communication==null)communication=new CommunicationRouteLease(new CommunicationRouteLease.Controls(){
                    public int mode(){return manager.getMode();}
                    public void communication(){manager.setMode(AudioManager.MODE_IN_COMMUNICATION);}
                    public boolean select(){if(Build.VERSION.SDK_INT>=31)return manager.setCommunicationDevice(bluetoothOutput);manager.startBluetoothSco();manager.setBluetoothScoOn(true);return true;}
                    public void clear(){if(Build.VERSION.SDK_INT>=31)manager.clearCommunicationDevice();else{try{manager.stopBluetoothSco();}finally{manager.setBluetoothScoOn(false);}}}
                    public void normal(){manager.setMode(AudioManager.MODE_NORMAL);}
                });
                if(!communication.acquire())throw new IllegalStateException("Bluetooth routing refused");
            }else if(communication!=null)communication.close();
            if(!recorder.setPreferredDevice(chosen))throw new IllegalStateException("Microphone routing refused");
            if(!listenerAdded){recorder.addOnRoutingChangedListener(r->observe(recorder),null);listenerAdded=true;}
            if(media&&required==Route.UNCONFIRMED)VoiceHealth.event("MEDIA_ROUTE","Preserving media output; using confirmed non-Bluetooth input");
        }catch(Exception error){if(communication!=null)communication.close();recorder.setPreferredDevice(null);observed="Requested microphone unavailable; checking actual input";}
    }
    private static boolean bluetooth(AudioDeviceInfo d){return d.getType()==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||(Build.VERSION.SDK_INT>=31&&d.getType()==AudioDeviceInfo.TYPE_BLE_HEADSET);}
    void reconcile(Context context,AudioRecord recorder,boolean allowBluetooth){
        if(manager==null||requiredRoute!=Route.UNCONFIRMED)return;
        boolean active=manager.isMusicActive();
        boolean lost=communication!=null&&communication.owned()&&capturedRoute(recorder)!=Route.HEADSET;
        if(active!=media||lost){if(communication!=null)communication.close();request(context,recorder,allowBluetooth,requiredRoute);}
    }
    /** Startup routing is asynchronous on Bluetooth. Discard startup audio until the requested
     * input has been stable for 300 ms; never label phone audio as headset evidence. */
    static void awaitInput(AudioRecord recorder,Route required) throws InterruptedException {
        long deadline=android.os.SystemClock.elapsedRealtime()+5000,stableSince=0;
        int previous=-1;short[] discard=new short[320];
        while(android.os.SystemClock.elapsedRealtime()<deadline){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            int n=recorder.read(discard,0,discard.length,AudioRecord.READ_NON_BLOCKING);
            if(n<0)throw new IllegalStateException("Microphone startup failed ("+n+")");
            observe(recorder);int id=routeId(recorder);long now=android.os.SystemClock.elapsedRealtime();
            if(id>=0&&observedRoute!=Route.UNCONFIRMED&&(required==Route.UNCONFIRMED||observedRoute==required)){
                if(id!=previous){previous=id;stableSince=now;}
                if(n>0&&now-stableSince>=300)return;
            }else{previous=-1;stableSince=0;}
            Thread.sleep(10);
        }
        throw new IllegalStateException(required==Route.HEADSET?"Headset microphone did not connect. Reconnect it and retry this take":"Microphone route did not settle. Retry this take");
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
    static Route capturedRoute(AudioRecord recorder){
        try{AudioDeviceInfo d=recorder.getRoutedDevice();if(d==null)return Route.UNCONFIRMED;
            int t=d.getType();
            if(t==AudioDeviceInfo.TYPE_BUILTIN_MIC)return Route.PHONE;
            if(t==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||t==AudioDeviceInfo.TYPE_WIRED_HEADSET||t==AudioDeviceInfo.TYPE_USB_HEADSET||t==AudioDeviceInfo.TYPE_USB_DEVICE||(Build.VERSION.SDK_INT>=31&&t==AudioDeviceInfo.TYPE_BLE_HEADSET))return Route.HEADSET;
        }catch(Exception ignored){}
        return Route.UNCONFIRMED;
    }
    static void observe(AudioRecord recorder){
        try{AudioDeviceInfo actual=recorder.getRoutedDevice();
            if(actual==null){observed="Recording input unconfirmed";observedRoute=Route.UNCONFIRMED;return;}
            boolean phone=actual.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC;
            observed=(phone?"Phone microphone":String.valueOf(actual.getProductName()))+" — confirmed input";
            boolean headset=actual.getType()==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||actual.getType()==AudioDeviceInfo.TYPE_WIRED_HEADSET
                ||actual.getType()==AudioDeviceInfo.TYPE_USB_HEADSET||actual.getType()==AudioDeviceInfo.TYPE_USB_DEVICE
                ||(Build.VERSION.SDK_INT>=31&&actual.getType()==AudioDeviceInfo.TYPE_BLE_HEADSET);
            observedRoute=phone?Route.PHONE:headset?Route.HEADSET:Route.UNCONFIRMED;
            IrisListeningService.currentMic=observed;
        }catch(Exception ignored){observed="Recording input unconfirmed";observedRoute=Route.UNCONFIRMED;}
    }
    public void close(){
        if(communication!=null)communication.close();
        observed="Microphone idle";observedRoute=Route.UNCONFIRMED;
    }
}
