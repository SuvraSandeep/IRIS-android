package com.iris.assistant;
import java.util.*;

/** Shared discoverable fact catalog and deterministic read-only question routing. */
public final class PhoneFacts {
    private PhoneFacts() { }
    public static final class Field {
        public final String key,label,group; final String aliases;
        Field(String key,String label,String group,String aliases){this.key=key;this.label=label;this.group=group;this.aliases=aliases;}
    }
    public static final Field[] FIELDS = {
        new Field("net_ip","Default connection IP addresses","network","my ip|phone ip|ip address|default ip"),
        new Field("accessory_battery","Accessory battery visibility","devices","watch battery|earbud battery|earbuds battery|headphone battery|bluetooth battery"),
        new Field("phone_ip_wifi","Phone IP on Wi-Fi","network","wifi ip|wi fi ip|wireless ip"),
        new Field("cell_ip","Cellular interface IP","network","cellular ip|mobile data ip|cell ip"),
        new Field("phone_ip6","Default interface IPv6","network","ipv6|ip v6"),
        new Field("gateway","Router / gateway","network","router ip|gateway|router address"),
        new Field("dns","DNS servers","network","dns|name servers"),
        new Field("net_interface","Default network interface","network","network interface"),
        new Field("net_mtu","Network MTU","network","mtu"),
        new Field("net_private_dns","Private DNS","network","private dns"),
        new Field("net_transport","Active connection","network","network|connection type|connected network"),
        new Field("net_internet","Internet validation","network","internet connection|internet status|online status"),
        new Field("wifi_name","Wi-Fi name","network","wifi name|wi fi name|ssid|which wifi|which wi fi|connected wifi"),
        new Field("wifi_signal","Wi-Fi signal","network","wifi signal|wi fi signal|rssi|signal strength"),
        new Field("wifi_freq","Wi-Fi frequency","network","wifi frequency|wi fi frequency|wifi band|wi fi band"),
        new Field("wifi_link_speed","Wi-Fi link speed","network","wifi speed|wi fi speed|link speed"),
        new Field("vpn","VPN","network","vpn"),
        new Field("metered","Connection cost","network","metered|connection cost"),
        new Field("public_ip","Public IP","network","public ip|external ip|internet ip"),
        new Field("iris_rx_rate","IRIS receive rate","network","download speed|receive rate|download rate"),
        new Field("iris_tx_rate","IRIS send rate","network","upload speed|send rate|upload rate"),
        new Field("iris_session_traffic","IRIS monitoring-session traffic","network","session traffic|iris data usage|iris traffic"),
        new Field("device_total_traffic","Device traffic since boot","network","total data usage|device data usage|device traffic"),
        new Field("traffic_content","Traffic visibility","network","data being transmitted|what data|packet contents|transmitted data|bluetooth data|watch data"),
        new Field("bt_state","Bluetooth","devices","bluetooth status|is bluetooth on"),
        new Field("bt_devices","Visible connected devices","devices","bluetooth devices|connected devices|connected to bluetooth|watch connected|earbuds connected"),
        new Field("audio_out","Available audio outputs","devices","audio output|speaker route|output route"),
        new Field("audio_in","IRIS microphone route","devices","microphone route|mic route|which microphone|which mic"),
        new Field("media_volume","Media volume","audio","media volume|volume level|how loud"),
        new Field("ringer_mode","Ringer mode","audio","ringer|silent mode|vibrate mode"),
        new Field("media_playback","Media playback","audio","media playing|media playback|music playing"),
        new Field("dnd","Do Not Disturb","audio","do not disturb|dnd"),
        new Field("battery","Battery level","resources","battery|charge level"),
        new Field("charging","Charging state","resources","charging|power source"),
        new Field("battery_temp","Battery temperature","resources","battery temperature|phone temperature|how hot|overheating"),
        new Field("battery_health","Battery health","resources","battery health|battery condition"),
        new Field("battery_voltage","Battery voltage","resources","battery voltage"),
        new Field("battery_technology","Battery technology","resources","battery technology|battery chemistry"),
        new Field("battery_discharge_rate","Battery discharge rate","resources","battery discharge rate|how fast is my battery draining|draining rate|battery drain rate|discharge rate|battery drain|how fast is it draining"),
        new Field("power_save","Battery saver","resources","battery saver|power saving|power saver"),
        new Field("thermal","Thermal status","resources","thermal status|thermal throttling"),
        new Field("ram_free","Available RAM","resources","free ram|available ram|free memory"),
        new Field("ram_total","Total RAM","resources","total ram|how much ram|ram capacity"),
        new Field("ram_used","Used RAM including cache","resources","used ram|ram usage|memory usage"),
        new Field("ram_iris","IRIS Java heap","resources","iris memory|iris ram|java heap"),
        new Field("memory_pressure","Memory pressure","resources","memory pressure|low memory"),
        new Field("storage_free","Available internal storage","resources","free storage|available storage|storage left|space left"),
        new Field("storage_total","Internal data volume size","resources","total storage|storage capacity"),
        new Field("storage_used","Used storage","resources","used storage|storage usage"),
        new Field("cpu_cores","Available logical CPU cores","system","cpu cores|processor cores|core count"),
        new Field("cpu_abi","CPU architectures","system","cpu architecture|processor architecture|supported abi|processor details"),
        new Field("cpu_temperature","CPU temperature","system","cpu temperature|processor temperature"),
        new Field("device","Device model","system","phone model|device model|which phone"),
        new Field("android","Android version","system","android version|os version|system version"),
        new Field("security_patch","Security patch","system","security patch|security update"),
        new Field("build_id","OS build","system","build number|build id"),
        new Field("app_version","IRIS version","system","iris version|app version"),
        new Field("device_uptime","Device uptime","system","phone uptime|device uptime|last reboot|since reboot"),
        new Field("service_uptime","IRIS service uptime","system","iris uptime|service uptime"),
        new Field("locale","System language","system","phone language|system language|locale"),
        new Field("timezone","Time zone","system","time zone|timezone"),
        new Field("airplane_mode","Airplane mode","system","airplane mode|flight mode"),
        new Field("device_locked","Device lock state","system","phone locked|device locked|lock state"),
        new Field("screen_lock","Screen lock protection","system","screen lock protection|screen lock configured"),
        new Field("display_resolution","Display resolution","display","screen resolution|display resolution|screen pixels"),
        new Field("display_refresh","Display refresh rate","display","refresh rate|screen hz|display hz"),
        new Field("display_density","Logical display density","display","pixel density|display density|screen dpi"),
        new Field("display_hdr","HDR display","display","hdr"),
        new Field("brightness","Brightness setting","display","brightness"),
        new Field("auto_brightness","Automatic brightness","display","auto brightness|automatic brightness|adaptive brightness"),
        new Field("font_scale","Font scale","display","font size|font scale|text size"),
        new Field("sensor_inventory","Available sensors","sensors","available sensors|sensor list|which sensors|sensors present|sensor inventory"),
        new Field("sensor_count","Sensor count","sensors","sensor count|how many sensors"),
        new Field("active_sensors","Hardware used by IRIS","sensors","active sensors|sensors being used|sensors are being used|sensors in use|sensors running|using sensors"),
        new Field("other_sensor_usage","Other apps sensor usage","sensors","other apps sensors|other apps using|which app is using"),
        new Field("location_enabled","Location setting","sensors","location enabled|gps enabled|location status|gps status"),
        new Field("wake_ready","Wake readiness","iris","wake ready|wake status|wake model|wake paused|why are you not listening|why aren t you listening"),
        new Field("owner_check","Voice enrollment","iris","voice enrolled|owner verification|owner check"),
        new Field("iris_service","IRIS service","iris","iris running|service status"),
        new Field("wake_phrase","Wake phrase","iris","wake phrase|wake word"),
    };
    private static String normalize(String s) {
        return s==null?"":s.toLowerCase(Locale.ROOT).replace("wi-fi","wifi").replace("wi fi","wifi")
            .replaceAll("[^a-z0-9 ]"," ").replaceAll("\\s+"," ").trim();
    }
    public static List<Field> group(String group){List<Field> out=new ArrayList<>();for(Field f:FIELDS)if(f.group.equals(group))out.add(f);return out;}
    public static List<Field> search(String text){
        String n=normalize(text);List<Field> out=new ArrayList<>();
        for(Field f:FIELDS)if(n.isEmpty()||normalize(f.label+" "+f.aliases).contains(n))out.add(f);
        return out;
    }
    public static List<Field> select(String text) {
        String n=normalize(text);
        // Never swallow actions, message dictation, media searches or external lookups.
        if(n.matches("^(?:please |can you |could you )*(?:turn|switch|enable|disable|set|change|increase|decrease|raise|lower|mute|unmute|call|dial|text|send|email|message|record|take|open|launch|play|search|find|google|share|delete|remove|connect|disconnect|pair|forget|remember|remind|stop|cancel)\\b.*"))return Collections.emptyList();
        if(n.matches(".*\\b(?:phone status|phone details|device details|system status|phone specifications|phone specs)\\b.*")) {
            List<Field> out=new ArrayList<>();for(String k:new String[]{"device","android","battery","storage_free","ram_free","net_transport","bt_devices"})for(Field f:FIELDS)if(f.key.equals(k))out.add(f);return out;
        }
        for(String g:new String[]{"network","display","sensors","resources","system","audio"}) {
            if(n.matches("(?:show |tell me |what are |give me )?(?:my |the |all )?"+g+" (?:details|status|information|specifications)"))return group(g);
        }
        LinkedHashSet<Field> out=new LinkedHashSet<>();
        for(String part:n.split("\\b(?:and|also|plus)\\b")) {
            int best=0;Field found=null;
            for(Field f:FIELDS)for(String alias:(f.aliases+"|"+f.label).split("\\|")) {
                String a=normalize(alias);
                if((" "+part.trim()+" ").contains(" "+a+" ")&&a.length()>best){best=a.length();found=f;}
            }
            if(found!=null)out.add(found);
        }
        return new ArrayList<>(out);
    }
    public static String answer(List<Field> fields,TelemetrySnapshot snapshot){
        StringBuilder out=new StringBuilder();
        for(Field f:fields){if(out.length()>0)out.append("\n");TelemetrySnapshot.Metric m=snapshot.get(f.key);
            out.append(f.label).append(": ");
            if(m.availability==TelemetrySnapshot.Availability.PERMISSION_REQUIRED)out.append("permission required. Open Settings to grant the relevant access.");
            else if(m.availability==TelemetrySnapshot.Availability.UNSUPPORTED)out.append("not available from Android on this device or current connection.");
            else out.append(m.display());
        }
        return out.toString();
    }
}
