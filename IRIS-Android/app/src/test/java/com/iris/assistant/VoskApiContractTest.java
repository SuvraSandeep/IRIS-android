package com.iris.assistant;

import org.junit.Test;
import org.vosk.SpeakerModel;
import org.vosk.Recognizer;
import static org.junit.Assert.*;

/** Uses the actual Gradle dependency, no fake Vosk classes or native model allocation. */
public class VoskApiContractTest {
    @Test public void packagedSpeakerApiIsAvailable() throws Exception {
        assertNotNull(SpeakerModel.class.getConstructor(String.class));
        assertEquals(void.class, Recognizer.class.getMethod("setSpeakerModel", SpeakerModel.class).getReturnType());
        assertTrue(AutoCloseable.class.isAssignableFrom(SpeakerModel.class));
    }
}
