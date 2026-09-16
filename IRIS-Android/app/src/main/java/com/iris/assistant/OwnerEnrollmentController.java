package com.iris.assistant;

import java.util.*;

/** Candidate samples are separate from active identity and from held-out validation. */
final class OwnerEnrollmentController {
    final List<float[]> normal=new ArrayList<>(),soft=new ArrayList<>(),validation=new ArrayList<>();
    void clear(){normal.clear();soft.clear();validation.clear();}
    void add(int index,float[] vector){
        if(!WakePolicy.owner(vector,vector,.99))throw new IllegalArgumentException("Invalid speaker evidence");
        List<float[]> target=index<OwnerTrainingPlan.NORMAL?normal:index<OwnerTrainingPlan.ENROLLMENT?soft:validation;
        // Reject near-identical replayed data within each enrollment group; validation remains independent.
        for(float[] previous:target)if(java.util.Arrays.equals(previous,vector))throw new IllegalArgumentException("Duplicate take; record a new sample");
        target.add(vector.clone());
    }
    OwnerVoiceProfile build(String phrase,String hash,double threshold)throws Exception{return OwnerVoiceProfile.create(phrase,hash,normal,soft,validation,threshold);}
}
