# IRIS 13.5.0 device acceptance tests

These are test cases, not results. Use a physical phone with a saved, validated owner profile. Record phone/Android version, app version (13.5.0 / build 358), mic route, condition, repetitions, outcomes and recovery time. Use Settings → endurance testing to label attempts; never count an automatically accepted cycle as proof that every spoken wake was heard.

This sheet covers everything that changed between 13.0.0 and 13.5.0. Run section 1 first and in full: it is the only part that can have made the product worse, because it altered the wake path. Everything else is additive and can be tested in any order.

Nothing in `TEST-13.0.0.md` is superseded. Treat that sheet as still current for media routing, endurance, feedback training and watch relay; this sheet adds to it.

**Before starting:** confirm Settings → the version footer reads 13.5.0, and that the owner profile still loads (Training tab shows a validated profile, not a re-enrol prompt). If the profile needs re-enrolling, stop — that is a defect, not a test precondition.

---

## 1. Wake recall tuning (13.0.2) — highest risk

Three gates that run **before** any speaker check were loosened to reduce owner misses: the quiet-speech gate (ambient multiplier 3 → 2.5), the training phrase-shape floor (.08 → .12), and the streaming duration band (0.45–2.2s → 0.40–2.4s). The owner-identity decision, accept threshold, negative bank and ECAPA weighting were deliberately **not** touched.

The only acceptable outcome is more owner wakes with **no** increase in non-owner acceptance. A recall gain bought with a false-accept is a regression, so run the rejection cases with the same rigour as the recall cases.

| Case | Procedure | Expected result |
|---|---|---|
| Quiet-speech recall | Say the wake phrase softly (normal indoor room, ~1m, the volume that previously failed): 30 labelled attempts | Record first-call successes. Compare against your pre-13.0.2 figure for the same condition; expect equal or better, never worse |
| Quiet speech, noisy room | Same soft delivery with a fan, TV or traffic audible: 30 labelled attempts | Record honestly. Some loss here is expected and acceptable; a large drop versus quiet indicates the multiplier change went too far |
| Distant / off-axis | Speak from 3m, and from behind the phone: 30 attempts each | Record. This is the case the duration-band widening should help most |
| Fast and slow delivery | Say the phrase deliberately fast (under ~0.5s) and deliberately drawn out (over ~2.2s): 20 each | Both should now be considered rather than discarded on length alone; owner verification still decides |
| Non-owner rejection | Have 3 different people say the wake phrase normally, loudly, and softly: 20 attempts each person | **Every attempt rejected.** Any acceptance is a release blocker |
| Recorded playback | Play a recording of the owner's wake phrase from a speaker at several volumes, 20 attempts | Rejected. Do not claim general replay resistance from this; record what was tested |
| TV and radio speech | Leave speech-heavy TV or radio running for 60 minutes with IRIS armed | Record unwanted wakes. Compare against your pre-13.0.2 baseline; must not increase |
| Overnight false-accept | Arm IRIS in a normally occupied room for 8 hours | Record every unwanted wake with timestamps. Must not increase versus baseline |
| Reject-reason split | After the above, open Settings → Wake reliability dashboard | Note the ratio of `OWNER_REJECTED` to `AUDIO_QUALITY` rejections. This decides whether the deferred second-chance VAD is worth building |

## 2. Wake timing margins (13.4.0)

The rolling audio ring went from 8s to 16s, and the speaker-model inference watchdog from 8s to 12s (client 14s). Both were chosen to be additive: when analysis keeps up, the extra capacity is never read.

| Case | Procedure | Expected result |
|---|---|---|
| Unchanged normal wake | 50 ordinary wake attempts in good conditions | Behaviour and latency indistinguishable from 13.3.0. Any added delay to a normal wake is a defect |
| Cold start | Reboot the phone, arm IRIS, wake immediately without warming the app: 20 attempts | Attempts that previously timed out should now complete. Record any that still fail |
| Throttled phone | Warm the phone (run a game or heavy download), or enable battery saver, then 20 wake attempts | Verification completes rather than being abandoned at 8s. Record actual response times |
| Analysis backlog | Speak the phrase while the phone is under heavy load so analysis briefly falls behind | Phrase is still analysed from retained audio instead of discarded |
| Genuinely stuck model | Debug build: stall the voice_models process during verification | Worker gives up first and reports failure; client does not time out ahead of it; no false acceptance; a fresh session can wake again |
| Memory cost | Monitor IRIS memory across a long armed session | Roughly 512KB extra for the larger ring. No growth over time (that would be a leak, not the ring) |

## 3. Phone history queries (13.3.0)

Requires `READ_CALL_LOG` and `READ_SMS`, which can now be granted from the dashboard in section 9.

The routing guard is the important part here: SMS-inbox phrasings require the word **"inbox"** specifically so they cannot steal commands from the existing notification reader. Section 3's regression rows are not optional.

| Case | Procedure | Expected result |
|---|---|---|
| Who called | "who called me", "who just called", "who called me today" | Names the most recent incoming caller, using the contact name where known |
| Missed calls | "any missed calls", "did I miss any calls", "how many missed calls" | Correct count and caller names. Both the adjective ("missed calls") and verb ("did I miss") forms must work |
| Recent calls | "recent calls", "my last few calls" | Short list, at most 5, newest first |
| Last inbox message | "read my inbox", "read my last inbox message", "what's in my inbox" | Reads the most recent received SMS body |
| Inbox from a person | "any inbox messages from Ana", "read the last inbox message from Ana" | Reads matching messages, or says plainly that no number could be found for that name |
| **Regression: notification phrasings** | "read my last text", "read my messages", "any new messages", "message from Ana", "how many texts" | All handled by the **notification** reader (WhatsApp/Slack/SMS notifications) exactly as before 13.3.0. None may trigger an SMS-inbox read or a permission prompt |
| **Regression: bare nouns** | "messages", "texts", "sms" alone | No action, no permission nag |
| **Regression: outgoing history** | "who did I call", "call history" | Still answered from IRIS's own records by the pre-existing handler |
| Unresolvable name | Ask for inbox messages from a name not in contacts | "I could not find a number for X, so I cannot check for messages from them." Must **not** claim there are no messages |
| Awkward contact name | Create a contact containing `%` and one containing `_`; ask for inbox messages from each | Matches that contact only. The characters must not behave as wildcards matching everything |
| Duplicate names | Two contacts with the same display name | Resolves to the same one consistently across repeated attempts |
| Permission denied | Revoke call-log and SMS access, then ask each question above | Spoken explanation of the missing permission. No crash, no silence, no false "you have no missed calls" |
| **Privacy: no persistence** | Ask for an inbox message, then open Settings → Command history and retry | The command appears; the **message body and caller number must not**. Check with activity logging set to full transcripts |
| Privacy: logs | After the above, export logs and search for the message text | Message contents absent |

## 4. Pronoun follow-ups (13.3.0)

| Case | Procedure | Expected result |
|---|---|---|
| Call back | Call or be called by someone, then "call him back" / "call her back" | Resolves to that person and goes through the **normal confirmation prompt** before dialling |
| Text a referent | After dealing with someone, "text her", "reply to them", "send him a message" | Enters the normal guided compose flow, which asks what to say |
| Give a ring | "give her a ring", "ring him back" | Same as call back |
| No referent yet | Fresh start with no recent contact, then "call him back" | Says it does not know who is meant. No wrong-number call |
| **Regression: bare redial** | "redial", "call that number again", "call back" (no pronoun) | Handled by the pre-existing redial command, unchanged |
| Confirmation still required | Any referent call | Never dials without the existing confirmation step |
| Wrong referent | Deliberately follow up after two different people in quick succession | Resolves to the most recent. Record any case where it picks the wrong one |

## 5. Notification summary (13.4.0)

| Case | Procedure | Expected result |
|---|---|---|
| Summarise | With 6+ notifications across 3 apps: "summarise my notifications", "what did I miss", "what's new" | Grouped count, busiest app first, e.g. "6 notifications: 3 from WhatsApp, 2 from Gmail and one from Slack." |
| Unapostrophed speech | Say "whats new" so speech-to-text produces no apostrophe | Still recognised as a summary request |
| **Regression: read out** | "read my notifications" | Reads them individually, exactly as before 13.4.0 |
| Many apps | Generate notifications across 6+ different apps | Names at most 4 apps, then "N more from M other apps". The two numbers must be different quantities and read sensibly |
| Dominant sender | 5+ notifications where one person sent more than half | Adds "Mostly from X." |
| No dominant sender | Notifications spread evenly across senders | No "Mostly from" clause |
| Few notifications | Only 2 notifications present | Summarises without a "Mostly from" clause |
| Empty | Clear all notifications, then ask | Says there is nothing waiting. No error |
| No notification access | Revoke notification access, then ask | Explains access is missing and how to grant it |
| Privacy | After summarising, check command history and logs | Only the count recorded, not senders or message contents |

## 6. Proactive suggestions (13.4.0)

Off by default. The critical property is restraint: this feature speaks uninvited, so over-triggering is worse than under-triggering.

| Case | Procedure | Expected result |
|---|---|---|
| Default off | Fresh install or freshly cleared settings; wake IRIS 10 times | Plain greeting every time. No suggestions at all |
| Enable | Settings → Personality & feedback → "Let IRIS speak up first" on | Toggle saves and survives leaving and re-entering Settings |
| Missed calls | With unread missed calls, wake IRIS | Greeting adds one short note about them |
| Low battery | Below 15% and **unplugged**, wake IRIS | One note about the battery |
| Low battery while charging | Below 15% but plugged in | **No** battery note |
| Notification backlog | 5+ pending notifications, no missed calls, battery fine | One note about notifications |
| Only one note | Arrange missed calls **and** low battery **and** notifications simultaneously | Exactly one note, and it is the missed calls (highest priority). Never a list |
| Cooldown | Trigger a suggestion, then wake again immediately and repeatedly for the next 90 minutes | No further suggestion until 90 minutes have passed |
| Quiet hours | Set the phone clock between 22:00 and 08:00, arrange a trigger, wake IRIS | Plain greeting. Silent regardless of what is pending |
| Never blocks the greeting | Revoke call-log access, disable notification access, then wake with suggestions on | Greeting still happens promptly. A failure to gather data must never delay or suppress the greeting |
| Turn off | Disable the toggle and wake 10 times | Plain greeting every time |

## 7. After-failed-call follow-up (13.4.0)

Covers "the call could not be **launched**", not "rang but nobody answered". No-answer detection is not implemented.

| Case | Procedure | Expected result |
|---|---|---|
| Launch failure | Force a launch failure (revoke `CALL_PHONE` after confirmation, or use a build with no dialer) and ask IRIS to call someone | Offers to text that person instead and hands to the normal compose flow |
| Cancel the offer | At the offer, say "cancel" | Stands down cleanly. No text sent, no call retried |
| Compose through | Accept and dictate a message | Goes through the existing compose flow including its read-back and confirmation |
| Number-only target | Trigger a launch failure for a raw number with no contact | Offers to text the number |
| No target | Trigger a launch failure with neither name nor number available | Plain apology, then rearms. No malformed text offer |
| **Scope check** | Call someone who does not answer, and someone who declines | IRIS does **not** offer to text. This is expected, not a defect — record it so the limit is documented |

## 8. Offline-only mode (13.4.0 / 13.5.0)

| Case | Procedure | Expected result |
|---|---|---|
| Enable | Settings → Server mode → "Offline-only mode" on | Saves; the server status line immediately reads "Blocked by offline-only mode." |
| Status honesty | Turn server mode **on** while offline-only is also on | Status still reads "Blocked by offline-only mode." It must not claim the server is in use |
| Chat blocked | Ask a question that would normally use the server brain | Answered locally or declined. Confirm via logs that no server request was attempted |
| Transcription blocked | Enable server Whisper, then speak a command | Local transcription used; no audio leaves the device |
| Voice blocked | Enable the server voice, then trigger a reply | Local text-to-speech used |
| Weather | "what's the weather" | Explains plainly that offline-only mode is on. Must not appear to try and then fail confusingly |
| Model downloads blocked | Clear app data so models are absent, enable offline-only, then start IRIS | Clear explanation that models cannot be downloaded. No silent hang, no partial download |
| Wake still works | With offline-only on, 30 wake attempts and several local commands | Full local function: wake, verification, training, calls, texts, torch, alarms |
| Disable | Turn offline-only off | Server paths available again per the separate server-mode setting |
| Airtight check | With offline-only on, monitor network traffic for the app across a 30-minute session | No outbound requests from IRIS. Note that Android **system** speech is a separate setting and may still use its own provider |

## 9. Permission dashboard (13.5.0)

| Case | Procedure | Expected result |
|---|---|---|
| Panel renders | Settings → "What IRIS can access" | Ten rows: microphone, contacts, place calls, send texts, call log, read SMS inbox, notification access, show notifications, camera, location |
| Accurate states | Compare each row against Android → Apps → IRIS → Permissions | Every row matches reality. A wrong row is a serious defect, since the whole point is an honest picture |
| Summary count | Note the headline | Matches the number of allowed rows, e.g. "7 of 10 allowed." |
| Essentials marked | Inspect rows | Microphone, contacts and place calls are marked essential; the others are not |
| Essential warning | Revoke microphone access, reopen Settings | A warning appears naming how many essential permissions are missing. It disappears once restored |
| Grant from the row | Tap a row that is not allowed (try call log and read SMS specifically) | The Android permission dialog appears. On allowing, **the row flips to allowed immediately** without leaving Settings |
| Grant unlocks the feature | After granting call log this way, immediately ask "who called me" | Answers. This is the limitation this dashboard was built to fix |
| Deny | Tap a row and deny | Row stays as not allowed. No crash, no repeated nagging |
| Don't ask again | Deny twice so Android blocks the dialog, then tap the row | Routed to the app settings page rather than silently doing nothing |
| Notification access | Tap the notification access row | Opens the **notification access** settings screen, not a permission dialog. Switching IRIS on there and returning shows the row as allowed |
| Show-notifications honesty | Grant notification permission, then turn IRIS notifications **off** in Android settings; reopen the panel | Row reports **not** allowed. Reporting it as allowed would be the bug this case exists to catch |
| That row's action | Tap the show-notifications row in the state above | Opens app settings (no dialog can fix it) |
| Allowed rows inert | Tap a row that is already allowed | Nothing happens. No dialog, no navigation |
| Open app permissions | Tap "Open Android app permissions" | Opens IRIS's permission page |
| Navigate away mid-request | Tap a row, and while the dialog is up switch to another tab, then respond to the dialog | No crash. The app must not attempt to update a view that is gone |
| Rotate and background | Rotate the phone and background/restore the app with the panel open | Panel re-renders with correct states |
| Accessibility | Explore the panel with TalkBack | Each row announces its name, whether it is allowed, and what it enables or what is missing. Not-allowed rows have a full-size touch target |

## 10. Interface changes (13.1.0 / 13.2.0)

| Case | Procedure | Expected result |
|---|---|---|
| **Accent tinting still works** | Settings → Appearance → change the accent colour; view Home, chips and tabs | Deck cards, chips and the active tab all take the new accent. This is a regression check: these surfaces became ripple drawables and the theme code had to be taught to look through the wrapper |
| Material You accent | On Android 12+, pick the wallpaper-derived swatch | Applies. Changing the wallpaper does not update it until re-picked (a known, accepted limitation) |
| Ripples | Tap deck cards, chips and bottom tabs | Visible press feedback on each |
| Reduce motion | Enable reduce motion, then use tabs, orb, tiles and the command bar | No crossfades, pulses or scaling. Everything still functional |
| Tab crossfade | With reduce motion off, switch tabs repeatedly | Content crossfades smoothly; no flicker or doubled content |
| Command bar button | Type text, then clear it | Glyph switches between send and microphone. Tapping it empty starts voice; tapping with text sends |
| Tile pulse | Watch the telemetry tiles while values change | Pulses only when a value actually changes, not on every refresh |
| Command bar divider | Scroll deck content up and down | Divider above the command bar fades in only while content continues below the fold |
| Active tab scale | Switch tabs | Selected tab is slightly larger as well as differently coloured |
| Empty states | Clear the activity stream; filter logs to no matches | Explanatory text, not a bare line |
| **Orb error ring** | Trigger an error state (e.g. wake while the mic is held by another app) | The thick error ring settles back after about a second. It must not stay thick indefinitely |
| Orb states | Observe resting, listening, processing, speaking, paused, blocked, error | Each visually distinct with its own caption |
| Wide screen | On a tablet or 600dp+ width | All four telemetry tiles in a single row |
| Landscape orb | Rotate to landscape | Orb is capped and does not push the rest of the deck off-screen |
| Text sizes | Set the largest system font and the app's largest text size | No clipped or truncated labels, especially the bottom tab labels and "Training" |
| Touch targets | With TalkBack's touch exploration, check Read Once and Continuous | Full 48dp targets; both announce their purpose |
| Screen reader noise | Explore Home with TalkBack | Decorative dots, separators and dividers are skipped. Telemetry announces as "label: value" |

## 11. Build and regression guards

| Case | Procedure | Expected result |
|---|---|---|
| CI green | Push and watch the full workflow | `test-speech.sh`, XML validation, unit tests and the APK build all pass. This is the only real Android type check; the dashboard and MainActivity are never compiled locally |
| XML validation catches breakage | Temporarily put `--` inside a comment in any `res/*.xml`, run `python tests/check-xml.py` | Fails with the file and position. This guard exists because exactly that defect reached CI in 13.4.0 |
| Offline suites | Run `bash scripts/test-speech.sh` | All suites pass, including 127 permission catalogue, 113 phone-history, 61 referent and 97 policy checks |
| Disabled test still disabled | Check `ActionLedgerTest` | Still `@Ignore`d and **unresolved**. If CI ever produces a report for it, capture `app/build/reports/tests/testDebugUnitTest/` so it can finally be diagnosed |
| Upgrade in place | Install 13.5.0 over an existing 13.3.0/13.4.0 install without clearing data | Owner profile, training, memories, routines and settings all survive. New settings default to off |
| Settings defaults | On a fresh install, check the new toggles | Offline-only off, proactive suggestions off |

---

## Automated coverage

Offline suites (`bash scripts/test-speech.sh`), all passing at 13.5.0:

- 127 permission catalogue checks — row completeness, core count pinned at 3, notification access pinned as special access, count clamping.
- 113 phone-history query checks — including 28 regression guards that pin the notification and redial phrasings to their existing handlers.
- 61 referent follow-up checks — every pattern requires a pronoun so bare redial phrasings are untouched.
- 97 assistant policy checks — quiet hours, summary grouping and sender maths, suggestion priority and cooldown.
- 195 phone-fact, 97 assistant-policy, 61 telemetry, 58 recorder-deadline, 55 plan/intent, 54 NLP, 44 local-planner, 40 wake, 31 owner-contract, 24 owner-training-stage, 22 app-integration, 18 personal-vocabulary and 14 battery-rate checks.
- Streaming phrase-in-speech, negative stream, bounded handoff, overrun and PCM-erasure checks.
- XML validation across 27 resource files plus the manifest, and 4 Python training-layout suites.
- Java parse of 127 main and 25 test sources.
- Node: watch timeout generation, action whitelist, authenticated relay headers.

## What automation cannot validate

The offline suites are pure-logic only. They cannot tell you anything about:

- **Acoustic recall or false accepts.** Section 1 is the entire evidence base for whether the wake tuning helped or hurt. No test result here substitutes for labelled device attempts.
- Real speaker output, microphone routing or Bluetooth behaviour.
- Native model hangs, cold-start timing or thermal throttling on a real phone.
- Whether call-log and SMS reads return correct data, since there is no real provider off-device.
- Whether any Android permission dialog, deep link or settings screen actually appears.
- Any rendering, ripple, contrast, animation or TalkBack behaviour.
- Battery consumption, OEM background restrictions and Zepp installation.

`MainActivity`, `IrisOrbView`, `PhoneHistoryReader` and the permission dashboard contain no offline-testable logic at all and have never been compiled outside CI. Treat sections 9 and 10 as entirely unverified until run on a device.
