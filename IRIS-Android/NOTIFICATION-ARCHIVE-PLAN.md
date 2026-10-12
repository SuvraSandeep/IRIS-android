# Notification archive and deleted-message detection

Append-only archive of incoming notifications from user-selected apps, able to flag messages the
sender later deleted or unsent, with voice query, instant alert, search, and export.

Status: **all phases complete** at v13.6.0 / build 359.

---

## Why this document exists

The design below contains several decisions that look wrong unless the reasoning is attached, and
at least three that a future contributor (human or AI) would plausibly "fix" and thereby break the
feature. Each is recorded with its reason. Do not reverse one without reading the reason first.

---

## Verified platform facts

These were checked against sources, not assumed. They constrain the whole design.

1. **WhatsApp deletion is detectable.** When a message is deleted for everyone, WhatsApp removes
   the existing notification and posts a **new** one reading "This message was deleted". So
   string-matching in `onNotificationPosted` works for WhatsApp.

2. **Instagram "unsent" is not confirmed to post anything.** It may silently remove the
   notification. String-matching alone would therefore do nothing for Instagram, which is why the
   removal-reason heuristic below exists.

3. **`onNotificationRemoved` carries a reason code** (3-argument overload, API 26+; `minSdk` is 26
   so it is available on every supported device). Confirmed values include `REASON_CLICK = 1`
   ("status bar reporting a notification click") and `REASON_CANCEL = 2` ("user dismissal"), plus
   `REASON_APP_CANCEL` for app-initiated removal. This lets us **exclude the owner** as the cause
   of a removal, which pure timing cannot do.

4. **Images are generally unavailable.** `Notification.EXTRA_PICTURE` exists, but WhatsApp does not
   set it for photo messages — the extras simply lack the key. A deleted photo is unrecoverable; we
   capture only the "📷 Photo" text. Media is stored opportunistically when an app does provide it,
   and never promised in the UI.

5. **The listener is system-bound, independent of `IrisListeningService`.** Archiving works when
   IRIS is not listening, when the mic is off, and after a reboot; the system rebinds it
   automatically. The real threat is the OEM battery manager, which is why gap markers exist.

6. **The hard ceiling on the whole feature:** if no notification was ever posted, there is nothing
   to catch. Muted chats, a chat already open on screen, or a message already read produce no
   notification, so a later deletion is undetectable. This must be stated in the UI, not implied
   away.

---

## Decisions

### Storage

| Decision | Reason |
|---|---|
| A **new** `NotificationArchive`, with `NotificationStore` left completely untouched | `NotificationStore` is a rolling 100-item store feeding three shipped features (notification summary, read-out, proactive suggestions). Making it unbounded would change all three — summaries would start counting months-old notifications. A separate store guarantees zero behaviour change elsewhere |
| Plain `SQLiteOpenHelper`, not Room | The project has no Room or AndroidX. Room needs two dependencies plus an annotation processor, adding CI risk. An unbounded archive queried by "most recent from sender X" also should not be loaded into memory wholesale, which rules out the app's usual JSON approach |
| Encrypted at rest, readable only via explicit export | See the correction below — the database is **not** app-level encrypted |
| Write-once enforced by **SQLite triggers**, not by convention | See below. This is the single most important structural decision |
| Archive only **ticked** apps | Deletion detection is only meaningful for messaging apps. Narrower capture means less private data stored |

### Write-once enforcement

The requirement was "the file should not have the permission to delete/edit/modify", alongside
manual per-row deletion. Those are compatible: **no automated deletion, manual deletion allowed.**
Enforced in the database engine rather than in code:

- `BEFORE UPDATE` trigger aborts if any **content** column changes. Only `is_deleted`,
  `is_suspected` and `deletion_time` are writable after insert, so the intercepted text is
  physically immutable.
- `BEFORE DELETE` trigger aborts unless an explicit authorisation flag is set in the same
  transaction. Only the long-press UI path sets it.

A future bug, refactor, or AI agent cannot silently overwrite or purge the archive; the engine
refuses. This is stronger than "the code only does INSERT" and it is testable.

### Detection

| Decision | Reason |
|---|---|
| Explicit placeholder match is **confirmed**; removal-reason heuristic is **suspected**, shown differently | A suspected flag is a guess. Presenting it identically to a confirmed one would make the whole archive untrustworthy |
| Suspected requires `APP_CANCELLED` **and** screen off/locked for the notification's whole life | `APP_CANCELLED` alone also fires when the owner reads a message in-app. Requiring screen-off means the owner could not have read it, which is what makes the signal meaningful rather than noise |
| Placeholder wins; heuristic suppressed for 60s after one arrives | Otherwise WhatsApp's remove-then-repost sequence double-flags one event |
| Deletion rules apply generically to **every** ticked app | Hardcoding WhatsApp and Instagram would silently fail for any app added later |
| English strings only | Owner decision. Non-English WhatsApp uses different wording and detection will silently fail — a stated limitation |
| "You deleted this message" is **not** a sender unsend | That is the owner's own deletion. Flagging it would be wrong |
| Flag the most recent message from that sender, label says so | WhatsApp's deletion notification does not identify which message was deleted. With three messages from one person and the second deleted, only the most recent can be flagged. The UI must admit this rather than imply precision |
| The placeholder notification is **not** stored as its own row | Pure noise. It is used to flag the original and record the deletion time |

### Correction: the archive is NOT encrypted

An earlier draft of this plan said "encrypted at rest". That was wrong and is corrected here.

`SecureStore` wraps JSON in AES-GCM via `AndroidKeyStore`, but there is no equivalent for SQLite
without adding **SQLCipher**, a native dependency the project does not have. So the archive is:

- in **app-private storage**, protected by the Android application sandbox;
- covered by the device's **full-disk encryption**, which is on by default on modern Android;
- **excluded from cloud backup and device-to-device transfer** (`backup_rules.xml` and
  `data_extraction_rules.xml` both exclude it explicitly, as well as it being outside their include
  lists), so it cannot leave the device;
- destroyed by uninstall or "Clear data".

In practice that means reading it requires root or an unlocked device. But it must **not** be
described to the owner as encrypted. If app-level encryption is wanted, SQLCipher is the route and
that is a deliberate dependency decision, not something to assume.

This also revises the Q7 answer recorded earlier: the archive is not "unreadable after a factory
reset because the key is device-bound" — it is simply **gone**, because the file is wiped and never
backed up. The practical conclusion is unchanged: **export is the only backup.**

### Size cap

1GB ceiling, **no automated eviction**. Warn at 80%, prompt hard at 95% asking the owner to clear a
date range or all.

**Correction to the original sketch:** this was first described as "keep capturing flagged messages
only when full", which is not implementable. At insert time nothing is yet known to have been
deleted — the placeholder arrives later. What actually survives a full archive is **flagging**:
`is_deleted`, `is_suspected` and `deletion_time` remain writable at every usage level, so the
archive freezes while deletion detection keeps working on everything already captured. A single
`KIND_CAPTURE_PAUSED_FULL` marker row records that capture stopped.

Marker rows are always permitted regardless of size; suppressing them when full would hide the very
fact that capture had stopped. A nonsensical size reading (negative size, zero ceiling) reports OK
rather than FULL, because a failing size check that reported FULL would silently stop all capture.

Context: text rows are roughly 200 bytes, so 1GB is about 5 million notifications — ~137 years at
100/day. The cap is effectively unreachable unless media is ever stored. Flagged rows are exempt
from any clearing suggestion.

### Other

- **On by default**, with a non-blocking first-run notice. Owner decision; the notice means the
  behaviour is stated once rather than silent.
- **Instant alert posted silently** (no sound or vibration). It is an archive marker, not something
  needing attention that second, and a deletion caught at 3am should not buzz the phone.
- **Export reminders** monthly or after 1,000 new rows, whichever first.
- **Export is the only backup.** `AndroidKeyStore` keys cannot leave the device, so after a factory
  reset or on a new phone the archive is permanently unreadable even from a full backup. Clearing
  app data or uninstalling also destroys it.
- **Capture while locked, require unlock to display.** Capture is unaffected by lock state (that is
  when the feature matters most). Displaying intercepted private messages on a locked screen is the
  part that needs the gate, and Android 10+ restricts background activity starts anyway.
- **Gap markers.** Listener connect/disconnect rows so a battery-manager kill or revoked access
  shows as a visible hole rather than an invisible one. An archive worth trusting must show where
  it was not looking.
- **Group sender parsing.** Parse `"Sender: message"` out of the text when the notification is a
  group conversation, keeping the raw title as well so nothing is lost.
- **UI lives inside the Activity tab**, not a sixth bottom tab. Five tabs already keep their labels
  at 11sp because "Training" nearly truncates; a sixth would force truncation.

---

## Must not break

- `NotificationStore` is not modified. Notification summary, read-out and proactive suggestions
  must behave identically.
- `CLEAR_NOTIFICATION_PATTERN` ("clear/**delete** my notifications") and
  `NotificationStore.clearAll()` must never touch the archive.
- Any new voice phrase must be proven not to collide with `NOTIFICATION_PATTERN`, which already
  matches `.*\b(any|check|latest|last|read|unread|new)\b.*\b(message|messages|text|texts)\b.*` —
  so a bare "any deleted messages" would be swallowed by the existing notification reader. New
  triggers therefore **require** the word "deleted" or "unsent" and dispatch ahead of it, with
  regression guards. This exact collision class caused a real defect earlier ("read my last text"
  was stolen from the notification handler), so it is treated as a hard requirement.
- Message bodies must never reach `CommandHistory` or `LogStore`. Persisting them was a privacy
  defect fixed in 13.4.0 and this feature could reintroduce it. Only counts and kinds are logged.
- No wake, training, audio or model changes. `AGENTS.md` locks those.

---

## Phases

Deliberately small and independently verifiable.

- [x] **Phase 1 — detection rules (pure).** `DeletedMessageRules.java`: placeholder strings, group
      sender parsing, dedup fingerprint, confirmed/suspected decision, status labels. No Android
      imports, so fully offline-tested (80 checks).
- [x] **Phase 2a — schema and append-only guarantee.** `ArchiveSchema.java`: DDL, write-once
      triggers, size policy. 70 pure policy checks, plus 36 checks in
      `tests/check-archive-schema.py` that execute the real DDL against Python's SQLite engine and
      prove edits and unauthorised deletes are refused.
- [x] **Phase 2b — database access.** `NotificationArchive.java` (`SQLiteOpenHelper`, no new deps):
      insert with unique-index dedup, flagging, filtered search, size reporting, authorised manual
      delete, orphan-deletion rows. Query semantics verified against real SQLite (48 checks).
- [x] **Phase 3 — capture.** Extend `IrisNotificationListener`: ticked-app filter, insert, placeholder
      flagging, 3-arg `onNotificationRemoved` heuristic, gap markers.
- [x] **Phase 4 — app picker.** Search box, messaging apps first, WhatsApp/Instagram variants
      auto-detected and pre-ticked.
- [x] **Phase 5 — archive UI.** Section in the Activity tab: red cards for confirmed, distinct
      styling for suspected, original text always visible, filters (app/sender/date/deleted-only)
      and free-text search, long-press delete, separate "delete entire archive".
- [x] **Phase 6 — export.** CSV plus readable `.txt` via the existing `ACTION_CREATE_DOCUMENT` flow,
      and the export reminder.
- [x] **Phase 7 — voice.** Classifier dispatched ahead of `NOTIFICATION_PATTERN`, yes/no follow-up to
      open the log, instant silent alert.
- [x] **Phase 8 — verification, version bump, `IRIS-FEATURES.html` entry, commit message.**

### How the append-only guarantee is actually verified

`SQLiteOpenHelper` is Android-only and Robolectric is not available offline, so the triggers cannot
be exercised by the Java suites. Python ships a real SQLite engine, so `check-archive-schema.py`
extracts the DDL **from `ArchiveSchema.java`** (not a copy, which could drift) and executes it,
asserting that: every content column rejects an UPDATE, the intercepted text survives tampering
attempts, the three flag columns remain writable, an unauthorised DELETE and bulk wipe are both
refused, an authorised delete succeeds, and the guard re-arms afterwards so authorisation cannot
leak.

### Fingerprint note

The dedup fingerprint includes **post time**. Without it, the same short text sent twice in one
conversation ("ok", then "ok" again) would collide and the second message would be silently lost.
Re-reading the shade after a reconnect replays the original post time, so duplicates from a reboot
are still suppressed.

---

## Edge cases

### Detection ceiling
- No notification ever posted (muted chat, chat open on screen, DND, app notifications off) →
  deletion undetectable.
- Message deleted before the phone received it → nothing arrives.
- Notification access revoked, or listener killed by the OEM battery manager → gap, covered by
  marker rows.
- Phone off or rebooting at the moment of arrival and deletion.
- `onListenerConnected` re-seeds from the active shade and would re-insert already-archived rows;
  dedup hashing prevents duplication on every reboot.

### False positives
- Someone literally types "this message was deleted".
- Owner dismisses a notification (excluded via `REASON_CANCEL`).
- Owner taps a notification (excluded via `REASON_CLICK`).
- Owner reads a message in-app, causing an app-cancel (mitigated by the screen-off requirement).
- A quoted or forwarded message containing the placeholder text.
- "You deleted this message" — owner's own deletion, explicitly not flagged.

### Matching
- Deletion placeholder arrives with no prior entry (message predates the archive, or arrived during
  a listener gap) → store as an orphan rather than drop it.
- Two contacts with the same display name.
- Sender renamed, or saved vs unsaved in contacts, so the title differs between messages.
- Several deletions in quick succession from one person.
- Group deletion where the title is the group, not the person.
- Group message whose body itself contains a colon → split on the **first** colon only.
- Group notification with no colon in the text → sender unknown, body kept whole.

### Dual, clone and multi-account
- Work profile uses the **identical** package name; only `sbn.getUser()` distinguishes it.
- Two accounts inside one app (two Instagram profiles) share a package name; only `subText` or
  extras can separate them, and neither is guaranteed present.
- Clone app uninstalled → label unresolvable, row shows the raw package name.
- No clone app currently installed, so clone labelling is generic and renameable rather than
  hardcoded to a specific wrapper.

### Integrity and storage
- Manual row deletion followed by the same message arriving again → it reappears. Expected.
- Deleting an original that a deletion flag points at leaves a dangling flag.
- Concurrent access: the listener runs on a binder thread while the UI reads.
- Disk full → insert fails; must not crash the listener, and must surface rather than fail silently.
- Corrupted or missing database file → recreate empty and keep running; never crash on a cleared or
  deleted store.
- Encrypted and device-bound, so a corrupted archive is unrecoverable; exports are the only backup.
- Archive full and owner ignores the prompt → flagged-only capture continues.

### Content
- Notification text may already be truncated by the sending app; we store what arrived. Stated in
  the UI.
- Deleted photos, voice notes and documents are unrecoverable; only the placeholder text is kept.
- Clock or timezone changes shift the rendering of past rows; epoch millis are stored and rendered
  in local time.
