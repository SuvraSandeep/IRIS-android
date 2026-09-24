package com.iris.assistant;
import java.util.*;
import org.json.JSONObject;
/** Cache only fully validated exact documents; callers receive isolated snapshots. */
final class OwnerProfileCache {
    private static final LinkedHashMap<String,OwnerVoiceProfile> cache=new LinkedHashMap<>(4,.75f,true);
    private static synchronized OwnerVoiceProfile validated(String json)throws Exception {
        OwnerVoiceProfile profile=cache.get(json);
        if(profile==null){profile=new OwnerVoiceProfile(new JSONObject(json));cache.put(json,profile);
            while(cache.size()>2)cache.remove(cache.keySet().iterator().next());}
        return profile;
    }
    static OwnerVoiceProfile read(String json)throws Exception {return new OwnerVoiceProfile(validated(json));}
    static double threshold(String json)throws Exception {return validated(json).threshold();}
}
