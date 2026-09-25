package com.iris.assistant;
import org.json.*;
/** Only completed, sufficiently supported command text can reach the intent router. */
final class CommandEvidence {
    static boolean clear(String json){
        try{JSONArray words=new JSONObject(json).getJSONArray("result");if(words.length()==0)return false;
            double total=0;for(int i=0;i<words.length();i++){double confidence=words.getJSONObject(i).getDouble("conf");
                if(!Double.isFinite(confidence)||confidence<.35||confidence>1)return false;total+=confidence;}
            return total/words.length()>=.65;
        }catch(Exception e){return false;}
    }
}
