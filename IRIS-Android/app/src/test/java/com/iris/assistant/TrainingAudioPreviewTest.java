package com.iris.assistant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;
import android.os.Looper;
import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class TrainingAudioPreviewTest {
    @Test public void playbackCannotStartWhileMicrophoneIsOwned(){
        Object lease=AudioCaptureCoordinator.acquire();assertNotNull(lease);
        try{TrainingAudioPreview preview=new TrainingAudioPreview(RuntimeEnvironment.getApplication());
            final String[] result={null};preview.speak("Hello Iris",message->result[0]=message);
            assertFalse(preview.busy());assertNotNull(result[0]);assertTrue(AudioCaptureCoordinator.busy());
        }finally{AudioCaptureCoordinator.release(lease);}
    }
    @Test public void failedPlaybackAndRepeatedStopReleaseLeaseAndCompleteOnlyOnce(){
        TrainingAudioPreview preview=new TrainingAudioPreview(RuntimeEnvironment.getApplication());int[] calls={0};
        preview.play(null,message->calls[0]++);preview.stop();preview.stop();
        assertTrue(AudioCaptureCoordinator.busy());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertFalse(preview.busy());assertFalse(AudioCaptureCoordinator.busy());assertEquals(1,calls[0]);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(25));assertEquals(1,calls[0]);
    }
}
