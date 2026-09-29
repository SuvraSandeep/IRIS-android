package com.iris.assistant;
import java.util.*;
/** Reports observed validation margins, not a predicted recognition percentage. */
final class VoiceRefinementReport {
 static String compare(OwnerVoiceProfile before,OwnerVoiceProfile after,boolean headset){try{
  return "\n\nHeld-out speaker checks for "+(headset?"headset":"phone")+": minimum score "+String.format(Locale.ROOT,"%.3f → %.3f",minimum(before,headset),minimum(after,headset))+"; required "+String.format(Locale.ROOT,"%.3f",after.threshold())+". All four saved checks must pass. Retest new, unsaved examples after this update; stored checks are not a field-accuracy estimate.";
 }catch(Exception e){return "\nValidation report unavailable; do not save this update.";}}
 static double minimum(OwnerVoiceProfile p,boolean h)throws Exception{
  List<float[]> es=p.ecapaList(h?"headsetEcapaValidation":"ecapaValidation",4,4),vs=p.voskList(h?"headsetVoskValidation":"voskValidation",4,4);double min=1;
  for(int i=0;i<4;i++)min=Math.min(min,WakePolicy.finalScore(es.get(i),h?p.headset.ecapaCentroid():p.ecapaCentroid(),vs.get(i),h?p.headset.voskCentroid():p.voskCentroid()));return min;
 }
}
