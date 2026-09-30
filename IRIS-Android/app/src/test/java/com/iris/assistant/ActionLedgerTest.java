package com.iris.assistant;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Real bug this pins: ActionLedger previously stored what IRIS actually did (file paths,
 * contact references, message targets) in a PLAINTEXT framework SQLite database, unlike its
 * siblings CommandHistory/LogStore/RoutineStore which all persist through encrypted SecureStore.
 * Rewritten to use SecureStore while preserving the exact same public API (record/recordSaved/
 * last/lastOf/lastReversible/markUndone/recent/count/timeline/clearAll, AutoCloseable) so every
 * existing caller keeps working unchanged.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ActionLedgerTest {
    @Test public void recordsPersistThroughEncryptedStorageNotPlaintext() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (ActionLedger ledger = new ActionLedger(context)) {
            ledger.clearAll();
            long id = ledger.record("camera_video", ActionLedger.OK, "Recorded a video", "Movies/IRIS", "", false);
            assertTrue(id >= 0);
            ActionLedger.Record last = ledger.last();
            assertNotNull(last);
            assertEquals("camera_video", last.intent);
            assertEquals("Movies/IRIS", last.location);
            assertEquals(1, ledger.count());
            // The on-disk file must be encrypted (SecureStore payload format: base64 IV,
            // base64 ciphertext), never contain the plaintext location/summary directly --
            // that is the actual defect this rewrite fixes.
            String raw = SecureStore.read(context, "action-ledger.json", "[]");
            assertFalse("plaintext summary must not appear in the stored file", raw.contains("Recorded a video"));
            assertFalse("plaintext location must not appear in the stored file", raw.contains("Movies/IRIS"));
        }
    }

    @Test public void recentOrdersNewestFirstAndRespectsLimit() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (ActionLedger ledger = new ActionLedger(context)) {
            ledger.clearAll();
            ledger.record("a", ActionLedger.OK, "first", "", "", false);
            ledger.record("b", ActionLedger.OK, "second", "", "", false);
            ledger.record("c", ActionLedger.OK, "third", "", "", false);
            java.util.List<ActionLedger.Record> recent = ledger.recent(2);
            assertEquals(2, recent.size());
            assertEquals("third", recent.get(0).summary);
            assertEquals("second", recent.get(1).summary);
        }
    }

    @Test public void lastOfFiltersByIntentSubstring() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (ActionLedger ledger = new ActionLedger(context)) {
            ledger.clearAll();
            ledger.record("camera_photo", ActionLedger.OK, "photo one", "", "", false);
            ledger.record("camera_video", ActionLedger.OK, "video one", "", "", false);
            ledger.record("sms_sent", ActionLedger.OK, "text one", "", "", false);
            ActionLedger.Record r = ledger.lastOf("camera");
            assertNotNull(r);
            assertEquals("camera_video", r.intent);
        }
    }

    @Test public void lastReversibleAndMarkUndoneRoundTrip() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (ActionLedger ledger = new ActionLedger(context)) {
            ledger.clearAll();
            long id = ledger.record("sms_sent", ActionLedger.OK, "text mom", "", "ref1", true);
            ActionLedger.Record r = ledger.lastReversible();
            assertNotNull(r);
            assertEquals(id, r.id);
            ledger.markUndone(id);
            assertNull(ledger.lastReversible());
            assertEquals("undone", ledger.recent(1).get(0).status);
        }
    }

    @Test public void clearAllEmptiesTheLedger() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (ActionLedger ledger = new ActionLedger(context)) {
            ledger.record("a", ActionLedger.OK, "one", "", "", false);
            ledger.clearAll();
            assertEquals(0, ledger.count());
            assertNull(ledger.last());
        }
    }

    @Test public void timelineDescribesEachRecordWithLocationAndAge() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (ActionLedger ledger = new ActionLedger(context)) {
            ledger.clearAll();
            assertTrue(ledger.timeline(5).startsWith("Nothing recorded yet"));
            ledger.recordSaved("screenshot", "Took a screenshot", "Pictures/IRIS");
            String timeline = ledger.timeline(5);
            assertTrue(timeline.contains("Took a screenshot"));
            assertTrue(timeline.contains("Pictures/IRIS"));
            assertTrue(timeline.contains("Kept for 30 days"));
        }
    }
}
