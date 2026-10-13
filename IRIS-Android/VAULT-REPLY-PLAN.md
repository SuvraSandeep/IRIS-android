# Receipt Vault: reply from a live row

Status: **plan, not yet implemented.** Approved scope is section 2 only. Auto-reply (section 7) is
feasibility analysis awaiting a decision.

Target version: **14.2.0**, versionCode 370.

---

## 1. Why this is narrow, and why that is correct

Replying is not IRIS sending a message. There is no API for a third-party app to send a WhatsApp
or Instagram message. What actually happens is that the messaging app attaches a `PendingIntent`
plus a `RemoteInput` field to its own notification, and anything holding that handle — a watch, a
car head unit, IRIS — fires it with text attached. The app itself then sends the message.

That handle is live, owned by the other app, and cannot be stored. It cannot be written to the
archive database, serialised, or reconstructed. **When the notification leaves the tray, the
ability to reply leaves with it.**

The existing `NotificationReplyActivity.send` already enforces this, and its guard is the model
this feature reuses rather than replaces:

```java
for (StatusBarNotification current : IrisNotificationListener.activeForReply()) {
    if (!current.getKey().equals(selected.getKey())) continue;
    Notification.Action action = IrisNotificationListener.replyAction(current);
    if (action == null || current.getPostTime() != selected.getPostTime()
            || !action.actionIntent.equals(IrisNotificationListener.replyAction(selected).actionIntent)) break;
```

Three things are verified before anything is sent: the notification key, the post time, and the
action intent itself. If the notification is gone, the loop matches nothing and the send is
abandoned. `activeForReply` carries the comment *"cached history must never be used to send a
reply"*, which is a deliberate contract, not an incidental one.

### The consequence worth stating plainly

A deleted message has no notification by definition — WhatsApp removes the original and posts the
"This message was deleted" placeholder, which is precisely how the Vault detects it. **The flagged
rows, the entire reason the Vault exists, can never support reply.** This feature will therefore
be absent on exactly the rows the owner is most likely to be looking at. That is not a defect to
be engineered around; it is the mechanism.

### Confirmed: this sends a message, not a threaded reply

The owner asked for "send the message to the chat, not reply to any specific message". That is
already what `RemoteInput` does: the app posts a normal message into the conversation. There is no
quote-threading involved and nothing extra is needed to get this behaviour.

---

## 2. Approved scope

A `REPLY` control on an archive row, shown **only** when that row's notification is still live and
still offers a reply action.

- Matching is by the `notif_key` already stored on every row against `activeForReply()`.
- When no match is found the control is **absent**, not disabled and not showing an error. A
  button that silently fails is worse than no button.
- Sending reuses the existing triple-verified path. No new send logic is written.
- The send is gated behind `authenticateThen`, because it is an outgoing write action under the
  owner-contract rules in `AGENTS.md`.
- Every send is written to `ActionLedger` with the honest status the existing code already uses:
  submitted to the app, delivery unverified.

### Deliberately out of scope

| Excluded | Reason |
| --- | --- |
| Reply to a dismissed notification | The PendingIntent no longer exists. Impossible, not hard. |
| Caching PendingIntents to reply later | Violates the documented `activeForReply` contract, dies on process restart, and risks firing a stale intent into the wrong conversation. |
| Reply to flagged/deleted rows | No notification exists by definition. |
| Reply to marker rows | They are not messages. |
| "Open chat" deep links | Owner declined. |

---

## 3. Edge cases

1. **Per-message rows share one notification key.** Since 14.0.0 one notification produces several
   rows. All of them will match the same live notification, so several rows may offer REPLY to the
   same conversation. Acceptable — it is the same chat — but the control must not imply it replies
   to that specific message.
2. **Notification dismissed while the Vault is open.** The list is not live. REPLY may be visible
   but the send will refuse. Must surface as a clear message, not a silent no-op.
3. **Notification replaced by a newer one.** Same key, different post time. The existing guard
   catches this via the post-time check and refuses. Correct: the owner was looking at older
   content than the handle now points at.
4. **App offers no reply action.** `replyAction` returns null; control absent.
5. **App offers multiple text fields.** `replyAction` already refuses these as ambiguous.
6. **Group summary notification.** `replyAction` already returns null for `FLAG_GROUP_SUMMARY`.
7. **Empty or whitespace-only reply.** Refuse before sending; an empty message is never intended.
8. **Authentication cancelled.** Nothing is sent, nothing is logged as sent.
9. **Work-profile duplicate.** Rows carry `user_handle`; key matching is already profile-specific.
10. **Send throws** (`PendingIntent.CanceledException`). Report failure honestly and record
    `FAILED` in the ledger rather than claiming success.
11. **Archive row deleted mid-compose.** Send still works — it depends on the notification, not the
    row — but the row is gone from the list. Harmless.
12. **Reply text containing newlines or emoji.** Passed through unchanged; the app handles it.

---

## 4. Must not break

- `activeForReply` keeps its contract and its comment. No new caller may pass it cached data.
- `NotificationReplyActivity` continues to work unchanged; this is an additional entry point to
  the same verified send, not a replacement.
- `NotificationStore` stays untouched.
- No message body reaches `LogStore` or `CommandHistory`. The ledger records that a reply was sent
  and to which package — never the text.
- Reply must not appear for flagged rows, so the Vault never implies a deleted message can be
  answered.

---

## 5. Phases

- [ ] **Phase 1** — Pure `ReplyEligibility`: decides from row state alone whether a row could ever
      be replyable (is a message, not flagged, has a notification key). Offline tests, including
      that a flagged row is never eligible.
- [ ] **Phase 2** — `IrisNotificationListener.liveReplyTargetFor(notifKey)`: returns the live
      `StatusBarNotification` whose key matches and which offers a reply action, else null.
- [ ] **Phase 3** — Extract the verified send out of `NotificationReplyActivity` into a shared
      `NotificationReply.send(...)` so both entry points use one implementation. Behaviour of the
      existing activity must be unchanged.
- [ ] **Phase 4** — Vault UI: conditional `REPLY` control, compose dialog, `authenticateThen`
      gate, honest result toast.
- [ ] **Phase 5** — Version bump to 14.2.0, `IRIS-FEATURES.html` entry, full suite verification.

---

## 6. Verification

- Offline: new `ReplyEligibilityTest` registered in `scripts/test-speech.sh`.
- `tests/check-unresolved-types.py` targets extended to the new files.
- `ParseSources`, XML validation, R.id resolution.
- **On-device, because none of this can be proven offline:** reply to a live notification arrives
  in the chat; REPLY is absent on an older row; REPLY on a row whose notification was dismissed
  mid-session reports a refusal rather than silently doing nothing; a flagged row never offers it.

---

## 7. Auto-reply: feasibility (NOT approved, decision needed)

### Mechanically easier than it sounds

Auto-reply is **more** technically reliable than Vault reply, for one reason: it would run inside
`onNotificationPosted`, the single moment the notification is guaranteed live. The PendingIntent is
in hand. No matching, no staleness, no window of expiry.

### But IRIS cannot write the reply

This is the limitation that reshapes the whole idea. The owner asked for "IRIS will read the
message and auto reply". IRIS has Vosk for speech recognition and ONNX Runtime for voiceprints. It
has **no language model and no text generation capability whatsoever**. It cannot read a message,
understand it, and compose an appropriate answer. Nothing in the dependency list makes that
possible, and adding it would mean shipping a local LLM — a different project entirely.

What is actually achievable is **rule-based canned replies**: fixed text, optionally selected by
matched keyword, sender, or time window. "I'm driving, will reply later." Useful, but it is an
away-message system, not a conversational agent. The gap between that and what "auto reply"
suggests is large enough that it should be agreed before any code is written.

### Risks, roughly in order of seriousness

1. **Impersonation.** Messages leave the owner's account that the owner did not write, and the
   recipient cannot tell. This is the one that deserves a hard think rather than a setting.
2. **No delivery verification.** The existing code is honest that delivery is unverified. An
   automatic sender would fire and never know whether anything arrived.
3. **Group leakage.** The owner specified one-to-one only. Group detection relies on
   `FLAG_GROUP_SUMMARY`, `EXTRA_IS_GROUP_CONVERSATION` and title-decoration parsing — good, not
   perfect. A single false negative means auto-replying to a group.
4. **Replying to the wrong thing.** The `"14 messages from 3 chats"` roll-up proved notification
   text is not always a message. `MessageRollup` guards this now, but any gap means replying to a
   number.
5. **Loops.** Two auto-responders will talk to each other indefinitely. Needs a per-conversation
   cooldown and a hard cap per thread per day.
6. **Owner-contract tension.** `AGENTS.md` locks authenticated write controls. Automatic sending
   is by definition unauthenticated at the moment of sending, so the authentication has to move to
   the point of *arming* the feature, with the scope of what it may do fixed at that time.

### Minimum safe shape, if it goes ahead

- Off by default; arming requires authentication.
- Explicit per-contact allowlist. Never "all chats".
- One-to-one only, with any group signal disqualifying the thread.
- Fixed owner-authored text. No generated content.
- Per-conversation cooldown plus a daily cap.
- Never replies to a roll-up, a placeholder, or a flagged row.
- Every send recorded in `ActionLedger`, visible to the owner.
- A visible indicator while armed, so it is never forgotten.

### Recommendation

Build section 2 first. It is small, reuses a verified path, and carries no impersonation risk.
Then decide on auto-reply knowing it will be an **away-message responder with a contact
allowlist**, not IRIS composing replies — and that the honest version of the feature announces
itself rather than pretending to be the owner.
