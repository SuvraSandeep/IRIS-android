package com.iris.assistant;
import java.util.List;
final class CommandAmbiguity {
 static boolean needsRepeat(List<String> alternatives,float[] scores){
  if(alternatives==null||alternatives.size()<2||scores==null||scores.length<2)return false;
  if(!Float.isFinite(scores[0])||!Float.isFinite(scores[1])||scores[0]<0||scores[1]<0||Math.abs(scores[0]-scores[1])>.12f)return false;
  Plan first=IntentParser.parse(alternatives.get(0)),second=IntentParser.parse(alternatives.get(1));
  if(first.isUnknown()||second.isUnknown())return false;
  return first.intent()!=second.intent()||((first.needsConfirmation()||second.needsConfirmation())&&!first.entities().equals(second.entities()));
 }
}
