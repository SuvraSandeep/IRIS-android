package com.iris.assistant;
public class PhoneCommandsTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        for(String text:new String[]{"where are you","where are you iris?","ring my phone","please find my phone"})check(SpeechText.findPhone(text),text);
        for(String text:new String[]{"text dad where are you","where are you going","ring John","find my phone number"})check(!SpeechText.findPhone(text),text);
        for(String text:new String[]{"is my phone charging","check whether my phone is charging","am I plugged in","is the charger connected","charging status"})check(SpeechText.chargingQuestion(text),text);
        for(String text:new String[]{"text dad my phone is charging","remind me to charge my phone","search charging stations"})check(!SpeechText.chargingQuestion(text),text);
        check(ChargingState.describe(2,2,50,100).contains("charging over USB"),"USB");
        check(ChargingState.describe(4,1,80,100).contains("plugged in, but"),"Paused charge");
        check(!ChargingState.describe(5,0,100,100).contains("plugged"),"Full unplugged");
        check(ChargingState.describe(-1,-1,-1,-1).contains("isn't reporting"),"Unknown isn't false");
        System.out.println("Passed phone finder routing and charging-state tests");
    }
}
