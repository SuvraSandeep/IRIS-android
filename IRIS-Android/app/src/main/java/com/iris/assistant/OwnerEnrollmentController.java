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
    /** For a spare enrollment take (index at or past ENROLLMENT, recorded because the batch
     *  needed 1-2 extra samples to calibrate — see MainActivity's ENROLLMENT boundary check).
     *  add(int,float[]) alone would misroute these into validation, since it treats any index
     *  >=ENROLLMENT as a verification take; the caller already knows which group the spare
     *  belongs to (whichever of normal/quiet currently has fewer samples) so it's passed
     *  explicitly instead of inferred from index. */
    void addSpare(boolean quiet,float[] vector){
        if(!WakePolicy.owner(vector,vector,.99))throw new IllegalArgumentException("Invalid speaker evidence");
        List<float[]> target=quiet?soft:normal;
        for(float[] previous:target)if(java.util.Arrays.equals(previous,vector))throw new IllegalArgumentException("Duplicate take; record a new sample");
        target.add(vector.clone());
    }
    OwnerVoiceProfile build(String phrase,String hash,double threshold)throws Exception{return OwnerVoiceProfile.create(phrase,hash,normal,soft,validation,threshold);}
}
