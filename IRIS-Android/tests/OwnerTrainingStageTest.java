package com.iris.assistant;
public final class OwnerTrainingStageTest {
 static int checks;static void check(boolean ok){checks++;if(!ok)throw new AssertionError("Training check "+checks);}
 public static void main(String[] args){
  OwnerTrainingStage stage=new OwnerTrainingStage();check(!stage.title().contains("Recording"));
  stage.enter(OwnerTrainingStage.Kind.SPEECH_MODEL,"Microphone not started",1000,90000);
  check(stage.busy());check(!stage.title().contains("Recording"));check(stage.elapsedSeconds(4000)==3);check(!stage.expired(90999));check(stage.expired(91000));
  stage.enter(OwnerTrainingStage.Kind.SPEAKER_MODEL,"Speaker",2000,60000);check(!stage.expired(61999));check(stage.expired(62000));
  stage.enter(OwnerTrainingStage.Kind.ANALYSIS,"Checking",10000,30000);check(stage.expired(40000));
  stage.enter(OwnerTrainingStage.Kind.RETRY,"Wrong phrase",40000,0);check(!stage.busy());check(!stage.expired(Long.MAX_VALUE));
  int enrollmentTakes=0,verificationTakes=0;
  for(int i=0;i<OwnerTrainingPlan.TOTAL;i++){
   if(OwnerTrainingPlan.verification(i))verificationTakes++;else enrollmentTakes++;
   check(!OwnerTrainingPlan.label(i).isEmpty());
  }
  check(enrollmentTakes==OwnerTrainingPlan.ENROLLMENT);check(verificationTakes==OwnerTrainingPlan.VERIFY);
  check(OwnerTrainingPlan.ENROLLMENT==4);check(OwnerTrainingPlan.VERIFY==4);check(OwnerTrainingPlan.TOTAL==8);
  System.out.println("Passed "+checks+" owner training stage/plan checks");
 }
}
