package com.iris.assistant;
import java.util.*;
public final class PhoneFactsTest {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void selects(String question,String key){List<PhoneFacts.Field> fs=PhoneFacts.select(question);check(fs.size()==1&&fs.get(0).key.equals(key),question+" -> "+(fs.isEmpty()?"none":fs.get(0).key));}
    public static void main(String[] args){
        selects("What is my Wi-Fi IP?","phone_ip_wifi");
        selects("What is my IP address?","net_ip");
        selects("What is my public IP?","public_ip");
        selects("What is my router IP?","gateway");
        selects("What is my private DNS?","net_private_dns");
        selects("Tell me my battery temperature","battery_temp");
        selects("What is my battery health?","battery_health");
        selects("What is my watch battery?","accessory_battery");
        selects("What is my CPU temperature?","cpu_temperature");
        selects("What is my total RAM?","ram_total");
        selects("Which sensors are being used?","active_sensors");
        selects("Which sensors are available?","sensor_inventory");
        selects("What is my screen refresh rate?","display_refresh");
        selects("What devices are connected to Bluetooth?","bt_devices");
        selects("What data is being transmitted?","traffic_content");
        selects("What is my security patch?","security_patch");
        selects("Why is wake paused?","wake_ready");
        check(PhoneFacts.select("Tell me my battery health and free storage").size()==2,"compound facts");
        for(String action:List.of("turn wifi off","set brightness to 50","call Battery Shop","send mom my IP address","play battery by Metallica","search battery health on YouTube","open network settings","connect bluetooth","please turn on bluetooth","record video","change my font size"))
            check(PhoneFacts.select(action).isEmpty(),"preserve action: "+action);
        check(PhoneFacts.select("hello there").isEmpty(),"unrelated conversation");
        check(PhoneFacts.select(null).isEmpty(),"null question");
        Set<String> keys=new HashSet<>();for(PhoneFacts.Field f:PhoneFacts.FIELDS){check(keys.add(f.key),"unique field "+f.key);check(!PhoneFacts.search(f.label).isEmpty(),"searchable "+f.label);}
        List<PhoneFacts.Field> fs=PhoneFacts.select("What is my Wi-Fi IP?");
        String unavailable=PhoneFacts.answer(fs,TelemetrySnapshot.empty());
        check(unavailable.contains("not available"),"unavailable is not off or zero");
        TelemetrySnapshot s=TelemetrySnapshot.builder(100).permission("phone_ip_wifi","WifiInfo").build();
        check(PhoneFacts.answer(fs,s).contains("permission required"),"permission explained");
        s=TelemetrySnapshot.builder(100).value("phone_ip_wifi","192.168.1.24","","LinkProperties",100).build();
        check(PhoneFacts.answer(fs,s).contains("192.168.1.24"),"answer uses snapshot value");
        check(PhoneFacts.answer(fs,s.agedAt(20000,1000)).contains("stale"),"stale disclosure");
        System.out.println("Passed "+checks+" phone-fact checks");
    }
}
