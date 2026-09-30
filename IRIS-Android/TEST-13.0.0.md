# IRIS 13.0.0 device acceptance tests

These are test cases, not results. Use a physical phone with a saved, validated owner profile. Record phone/Android version, app version, mic route, condition, repetitions, outcomes and recovery time. Use Settings → endurance testing to label attempts; never count an automatically accepted cycle as proof that every spoken wake was heard.

| Case | Procedure | Expected result |
|---|---|---|
| Speaker music | Disconnect headsets, enable IRIS, start music; stop/restart IRIS and music in both orders, 20 times | Music uses the main media speaker; no persistent earpiece routing |
| Bluetooth music | Connect headphones or watch, start music before and after IRIS, 20 times | Selected media output retained; actual input displayed; phone profile used if phone mic is selected |
| Routing failure | Disconnect Bluetooth during selection, repeat 20 times | IRIS releases its communication mode and recovers a confirmed input |
| Real call | Make/receive a call with IRIS active, then hang up | Call routing is not taken over; IRIS recovers after mic access returns |
| First-call wake | In quiet, screen off, music, headset and soft/distant conditions: 30 separately labelled attempts each | Record first-call successes/misses honestly; each accepted wake acknowledges and rearms |
| Endurance | 500 wake → command → response cycles, including idle gaps | No session permanently stops; failures and recovery times captured |
| Local greeting | Disconnect Internet; say a standalone wake 30 times | Local greeting/cue after owner verification, followed by command capture |
| Inline command | Say wake plus charging query without a pause, 30 times | Command is retained rather than lost during greeting |
| Native failure | Debug build: terminate or deliberately stall the voice_models process during verification and decoding | No false acceptance; pending operation fails; a fresh session can wake again |
| Stale results | Trigger recovery while a result or TTS callback is pending | Old callback cannot accept a wake or finish a new session |
| Charging | Plug/unplug charger; test charging, full while plugged, and unplugged states | Reply matches current battery status; no stale charging claim |
| Phone finder | Ask “where are you”/“where are you Iris” in normal, silent and DND modes with required access granted | Finder rings where permitted; stop action works; prior audio state restored |
| Finder denied access | Revoke DND access and repeat | Explains permission limitation; does not claim it bypassed DND |
| Feedback success | Record fresh feedback, authenticate and pass all four held-out checks | Preview scores shown; update saved only against current revision |
| Feedback rejection | Wrong speaker, missing model, invalid vectors, failed held-out take, stale revision | Update/wake rejected; existing valid profile remains recoverable |
| Feedback benefit | Repeat the same labelled condition set before and after refinement | Compare first-call recall and unwanted wakes; no automatic improvement claim |
| Watch loopback | Enable bridge; use Test phone bridge; disable/revoke token and repeat | Authenticated status succeeds only with current configuration |
| Watch relay | On installed compatible watch, status/find/stop over Zepp; test phone disconnected and late reply | Correct result or bounded error; late results cannot overwrite a new request |
| Background | Lock phone for 30 minutes, battery saver on/off, interrupt with camera/recorder, then wake | Record OEM restrictions; recovery works when Android returns microphone access |

## Automated coverage

- Speech/parser/owner-policy/recorder-flow regression scripts.
- JVM: failed route acquisition, cleanup exceptions, foreign call mode and 500 release cycles.
- JVM: stale/duplicate worker replies, disconnection and 500 reply cycles.
- JVM: 500 simulated voice session recovery cycles and honest diagnostic labels.
- Node: watch timeout generation, action whitelist and authenticated relay headers.

Automated simulations cannot validate acoustic recall, real speaker output, native model hangs on a device, battery consumption, or Zepp installation.
