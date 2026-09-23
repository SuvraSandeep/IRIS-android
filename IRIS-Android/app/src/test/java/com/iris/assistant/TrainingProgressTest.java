package com.iris.assistant;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class TrainingProgressTest {
    JSONObject checkpoint()throws Exception {
        OwnerVoiceProfile p=OwnerVoiceProfileTest.profileVoskOnly();
        return new JSONObject().put("schema",3).put("phrase",p.phrase()).put("takeIndex",8)
            .put("sessionRoute","PHONE").put("baseRevision","revision").put("modelHash",p.hash())
            .put("ecapaTakes",p.data.getJSONArray("ecapaSamples")).put("voskTakes",p.data.getJSONArray("voskSamples"))
            .put("ecapaHeldOut",p.data.getJSONArray("ecapaValidation")).put("voskHeldOut",p.data.getJSONArray("voskValidation"))
            .put("phraseTakes",p.phraseEvidence.data.getJSONArray("samples")).put("phraseHeldOut",p.phraseEvidence.data.getJSONArray("validation"));
    }
    @Test public void completedVoskOnlySessionSurvivesResume()throws Exception {
        TrainingProgress.Data data=TrainingProgress.parse(checkpoint().toString(),"revision",false,.65);
        assertNotNull(data);assertEquals(8,data.takeIndex);assertEquals(4,data.phraseHeldOut.size());assertEquals(0,data.ecapaTakes.get(0).length);
    }
    @Test public void routeAndRevisionMustMatch()throws Exception {
        assertNull(TrainingProgress.parse(checkpoint().toString(),"changed",false,.65));
        assertNull(TrainingProgress.parse(checkpoint().toString(),"revision",true,.65));
        assertNotNull(TrainingProgress.parse(checkpoint().put("sessionRoute","HEADSET").toString(),"revision",true,.65));
    }
    @Test public void mismatchedPairsAndImpossibleIndexReject()throws Exception {
        assertNull(TrainingProgress.parse(checkpoint().put("voskTakes",new JSONArray()).toString(),"revision",false,.65));
        assertNull(TrainingProgress.parse(checkpoint().put("takeIndex",9).toString(),"revision",false,.65));
        assertNull(TrainingProgress.parse(checkpoint().put("schema",2).toString(),"revision",false,.65));
    }
    @Test public void missingOrWrongPhraseValidationRejects()throws Exception {
        assertNull(TrainingProgress.parse(checkpoint().put("phraseHeldOut",new JSONArray()).toString(),"revision",false,.65));
        JSONObject j=checkpoint();JSONArray bad=RecordedPhrase.array(java.util.Collections.singletonList(OwnerVoiceProfileTest.pattern(1)));
        j.getJSONArray("phraseHeldOut").put(0,bad.get(0));
        assertNull(TrainingProgress.parse(j.toString(),"revision",false,.65));
    }
}
