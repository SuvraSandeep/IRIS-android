package com.iris.assistant;
final class SmsIntentPolicy {
    static boolean mayAddressContact(String rest){
        if(rest==null)return false;
        String text=rest.trim().toLowerCase(java.util.Locale.ROOT);
        if(text.isEmpty())return false;
        String first=text.split("\\s+",2)[0];
        return !java.util.Arrays.asList("me","myself","us","i","what","when","where","why","how","the","a","an","that","this").contains(first);
    }
}
