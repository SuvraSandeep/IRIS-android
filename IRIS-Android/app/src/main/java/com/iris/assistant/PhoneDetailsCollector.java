package com.iris.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.provider.Settings;

/** Additional local facts. Reads state only: no network probes, scans or sensor activation. */
public final class PhoneDetailsCollector {
    private PhoneDetailsCollector() { }
    public static void contribute(Context c, TelemetrySnapshot.Builder b) {
        long now = SystemClock.elapsedRealtime();
        b.value("security_patch", Build.VERSION.SECURITY_PATCH, "", "Build.VERSION", now);
        b.value("cpu_abi", android.text.TextUtils.join(", ", Build.SUPPORTED_ABIS), "", "Build", now);
        b.value("cpu_cores", String.valueOf(Runtime.getRuntime().availableProcessors()), "logical processors available to IRIS", "Runtime", now);
        b.value("build_id", Build.DISPLAY, "", "Build", now);
        b.value("locale", java.util.Locale.getDefault().toLanguageTag(), "", "Locale", now);
        b.value("timezone", java.util.TimeZone.getDefault().getID(), "", "TimeZone", now);
        b.value("accessory_battery", "Not exposed through this dashboard; use the accessory companion app", "", "Platform limitation", now);
        b.value("cpu_temperature", "Android does not expose a general public CPU-temperature reading", "", "Platform limitation", now);
        b.value("traffic_content", "Only IRIS byte counts and its own operations are observable. Other apps' message contents and Bluetooth payloads are unavailable.", "", "Platform limitation", now);
        b.value("other_sensor_usage", "Other apps' sensor usage is unavailable to IRIS", "", "Platform limitation", now);
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                android.app.ActivityManager.MemoryInfo m = new android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(m);
                b.value("ram_total", TelemetrySnapshot.bytes(m.totalMem), "", "ActivityManager", now);
                b.value("ram_used", TelemetrySnapshot.bytes(Math.max(0,m.totalMem-m.availMem)), "(total minus available, includes cache)", "ActivityManager", now);
                b.value("memory_pressure", m.lowMemory ? "Low-memory condition" : "Normal", "", "ActivityManager", now);
            }
            android.os.StatFs fs = new android.os.StatFs(c.getFilesDir().getAbsolutePath());
            b.value("storage_total", TelemetrySnapshot.bytes(fs.getTotalBytes()), "(app data volume)", "StatFs", now);
            b.value("storage_used", TelemetrySnapshot.bytes(fs.getTotalBytes()-fs.getFreeBytes()), "(app data volume)", "StatFs", now);
            b.value("storage_reserved", TelemetrySnapshot.bytes(Math.max(0,fs.getFreeBytes()-fs.getAvailableBytes())), "", "StatFs", now);
        } catch (RuntimeException ignored) { }
        try {
            Intent i = c.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i != null) {
                int v=i.getIntExtra(BatteryManager.EXTRA_VOLTAGE,-1);
                if(v>0)b.value("battery_voltage",String.format(java.util.Locale.US,"%.3f",v/1000.0),"V","Battery broadcast",now);
                b.value("battery_technology",i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),"","Battery broadcast",now);
                int h=i.getIntExtra(BatteryManager.EXTRA_HEALTH,BatteryManager.BATTERY_HEALTH_UNKNOWN);
                String health=h==BatteryManager.BATTERY_HEALTH_GOOD ? "Good (platform status, not remaining capacity)"
                        : h==BatteryManager.BATTERY_HEALTH_OVERHEAT ? "Overheated"
                        : h==BatteryManager.BATTERY_HEALTH_DEAD ? "Dead"
                        : h==BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE ? "Over voltage"
                        : h==BatteryManager.BATTERY_HEALTH_COLD ? "Cold" : "";
                b.value("battery_health",health,"","Battery broadcast",now);
            }
        } catch(RuntimeException ignored) { }
        try {
            android.hardware.display.DisplayManager dm=(android.hardware.display.DisplayManager)c.getSystemService(Context.DISPLAY_SERVICE);
            android.view.Display d=dm==null?null:dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if(d!=null){
                android.view.Display.Mode m=d.getMode();
                b.value("display_resolution",m.getPhysicalWidth()+" × "+m.getPhysicalHeight(),"px (active mode)","Display.Mode",now);
                b.value("display_refresh",String.format(java.util.Locale.US,"%.1f",m.getRefreshRate()),"Hz (active mode)","Display.Mode",now);
                b.value("display_hdr",d.getHdrCapabilities().getSupportedHdrTypes().length>0?"Supported":"Not advertised","","Display",now);
            }
            b.value("display_density",String.valueOf(c.getResources().getDisplayMetrics().densityDpi),"dpi (logical)","DisplayMetrics",now);
            b.value("font_scale",String.format(java.util.Locale.US,"%.2f",c.getResources().getConfiguration().fontScale),"×","Configuration",now);
            int brightness=Settings.System.getInt(c.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,-1);
            if(brightness>=0)b.value("brightness",String.valueOf(brightness),"(system setting; not measured luminance)","Settings.System",now);
            int mode=Settings.System.getInt(c.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE,-1);
            if(mode>=0)b.value("auto_brightness",mode==Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC?"On":"Off","","Settings.System",now);
        }catch(RuntimeException ignored) { }
        try {
            android.media.AudioManager am=(android.media.AudioManager)c.getSystemService(Context.AUDIO_SERVICE);
            if(am!=null){
                b.value("media_volume",am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)+" / "+am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC),"","AudioManager",now);
                b.value("ringer_mode",am.getRingerMode()==android.media.AudioManager.RINGER_MODE_NORMAL?"Normal":am.getRingerMode()==android.media.AudioManager.RINGER_MODE_VIBRATE?"Vibrate":"Silent","","AudioManager",now);
                b.value("media_playback",am.isMusicActive()?"Media playback detected":"No active music stream detected","","AudioManager",now);
            }
            android.app.NotificationManager nm=(android.app.NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
            if(nm!=null){
                int f=nm.getCurrentInterruptionFilter();
                b.value("dnd", f==1?"Off":f==2?"Priority only":f==3?"No interruptions":f==4?"Alarms only":"", "","NotificationManager",now);
            }
            int airplane=Settings.Global.getInt(c.getContentResolver(),Settings.Global.AIRPLANE_MODE_ON,-1);
            if(airplane>=0)b.value("airplane_mode",airplane==1?"On":"Off","","Settings.Global",now);
            android.app.KeyguardManager kg=(android.app.KeyguardManager)c.getSystemService(Context.KEYGUARD_SERVICE);
            if(kg!=null){b.value("device_locked",kg.isDeviceLocked()?"Locked":"Unlocked","","KeyguardManager",now);
                b.value("screen_lock",kg.isDeviceSecure()?"Configured":"Not configured","","KeyguardManager",now);}
            android.location.LocationManager lm=(android.location.LocationManager)c.getSystemService(Context.LOCATION_SERVICE);
            if(lm!=null && Build.VERSION.SDK_INT>=28)b.value("location_enabled",lm.isLocationEnabled()?"On":"Off","","LocationManager",now);
        }catch(RuntimeException ignored) { }
        try {
            android.hardware.SensorManager sm=(android.hardware.SensorManager)c.getSystemService(Context.SENSOR_SERVICE);
            if(sm!=null){java.util.List<android.hardware.Sensor> list=sm.getSensorList(android.hardware.Sensor.TYPE_ALL);
                b.value("sensor_count",String.valueOf(list.size()),"sensors advertised","SensorManager",now);
                StringBuilder names=new StringBuilder();
                for(android.hardware.Sensor s:list){if(names.length()>0)names.append("; ");names.append(s.getName());}
                b.value("sensor_inventory",names.length()==0?"None advertised":names.toString(),"","SensorManager",now);}
            StringBuilder active=new StringBuilder();
            for(java.util.Map.Entry<IrisSensorUsageRegistry.Hardware,IrisSensorUsageRegistry.Usage> e:IrisSensorUsageRegistry.active().entrySet()){
                if(active.length()>0)active.append("; ");active.append(IrisSensorUsageRegistry.label(e.getKey())).append(": ").append(e.getValue().purpose);
            }
            b.value("active_sensors",active.length()==0?"No hardware use reported by IRIS":active.toString(),"","IRIS usage registry",now);
        }catch(RuntimeException ignored) { }
    }
}
