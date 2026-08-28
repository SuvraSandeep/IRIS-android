# IRIS Training Module Redesign — v0.4.0

## Current Problems

1. **Everything on one screen, no guided flow** — Wake training, contact training, profile management, testing, and import/export all dumped into one scrolling page. New users don't know what to do first.

2. **Two training systems with no visual distinction** — Wake phrase (acoustic DSP) and contact commands (speech-to-text) look identical but work completely differently.

3. **No step-by-step wizard** — 3-sample training has no progress indicator. Status text updates feel random.

4. **Wake phrase starts with typing** — Confusing to type the phrase you're about to speak.

5. **Contact training starts immediately** — No countdown, no preparation after picking a contact.

6. **Test buttons buried and ambiguous** — "Test" and "Test a trained command — no call" look like secondary buttons lost in the page.

7. **Profile management at the bottom** — Can't see trained contacts without scrolling past everything.

8. **No visual feedback during recording** — Only text changes. No animation, no mic indicator.

---

## New Layout Structure — 3 Section Cards

### Section 1: 🎙️ Wake Phrase (top card)

**Not trained state:**
```
┌─────────────────────────────────────┐
│  🎙️  WAKE PHRASE                    │
│                                      │
│  ⚠️  Not configured                 │
│  Choose any word or phrase. IRIS     │
│  listens for it to wake up.         │
│                                      │
│  [ Set Up Wake Phrase ]              │
└─────────────────────────────────────┘
```

**Trained state:**
```
┌─────────────────────────────────────┐
│  🎙️  WAKE PHRASE                    │
│                                      │
│  ✅ "Nova" • 3 samples • encrypted  │
│  Sensitivity: 1.24                   │
│                                      │
│  [ Retrain ]        [ Test ]         │
└─────────────────────────────────────┘
```

**Training wizard state (replaces the card content):**
```
┌─────────────────────────────────────┐
│  Step 1 of 3        ● ○ ○           │
│                                      │
│  Say "Nova" now                      │
│  🔴 Recording...                    │
│                                      │
│  [ Cancel ]                          │
└─────────────────────────────────────┘
```

**After each sample:**
```
┌─────────────────────────────────────┐
│  Step 2 of 3        ● ● ○           │
│                                      │
│  ✅ Clear • 6.2× noise              │
│  Say "Nova" again                    │
│                                      │
│  🔴 Recording...                    │
│  [ Cancel ]                          │
└─────────────────────────────────────┘
```

### Section 2: 📇 Contact Commands (middle card + list)

**Empty state:**
```
┌─────────────────────────────────────┐
│  📇  CONTACT COMMANDS               │
│                                      │
│  0 contacts • 0 phrases             │
│  Teach IRIS how you naturally ask    │
│  to call someone.                    │
│                                      │
│  [ + Train New Contact ]             │
└─────────────────────────────────────┘
```

**With trained contacts:**
```
┌─────────────────────────────────────┐
│  📇  CONTACT COMMANDS               │
│  3 contacts • 7 phrases             │
│                                      │
│  [ + Train New Contact ]             │
└─────────────────────────────────────┘

┌─ Mom ────────────────────────────── ┐
│  "Call Mom" • "Ring Maa"            │
│                          [ Delete ] │
└─────────────────────────────────────┘
┌─ Rahul ──────────────────────────── ┐
│  "Call Rahul" • "Phone Rahul bhai"  │
│                          [ Delete ] │
└─────────────────────────────────────┘
```

**Contact training wizard (replaces the card):**
```
┌─────────────────────────────────────┐
│  Training: Mom       ● ○ ○          │
│                                      │
│  Sample 1 of 3                       │
│  Say how you'd ask IRIS to call Mom  │
│                                      │
│  🔴 Listening...                    │
│  "Call Mo—"  (partial transcript)    │
│                                      │
│  [ Cancel ]                          │
└─────────────────────────────────────┘
```

### Section 3: 🧪 Test & Transfer (bottom card)

```
┌─────────────────────────────────────┐
│  🧪  TEST & TRANSFER                │
│                                      │
│  [ Test Wake Phrase ]  (disabled if  │
│  [ Test Command ]       not trained) │
│                                      │
│  ─────────────────────               │
│  [ Export Profile ] [ Import ]       │
└─────────────────────────────────────┘
```

---

## Training Wizard Flow

### Wake phrase wizard

1. User types wake phrase label (or keeps existing)
2. Taps "Set Up Wake Phrase" / "Retrain"
3. Card transforms to wizard → Step 1/3
4. `WakeWordEngine.captureOne()` starts recording
5. User speaks → onSample callback
6. Show quality feedback (Clear ✅ / Usable ⚠️ / Too noisy ❌)
7. If too noisy → retry same step with message
8. If OK → advance to Step 2/3, auto-start next recording after 750ms
9. After Step 3/3 → `ProfileStore.setWakeProfile()` saves
10. Card returns to trained state with updated info

### Contact command wizard

1. User taps "+ Train New Contact"
2. Contact picker opens
3. After selection, card transforms to wizard → Step 1/3
4. `SpeechRecognizer.startListening()` starts
5. User speaks → onResults callback
6. Show quality + recognized text ("Call Mom" — Clear ✅)
7. Advance to Step 2/3, auto-start next after 750ms
8. After Step 3/3 → `ProfileStore.addTraining()` saves
9. Card returns to normal, contact appears in list below

---

## Files to Change

| File | Change |
|------|--------|
| `view_training.xml` | Complete rewrite — 3 section cards |
| `MainActivity.java` | Refactor `showTraining()`, wizard UI updates in `captureNextWakeSample()`, `recordNextTrainingSample()`, `finishWakeTraining()`, `finishContactTraining()` |
| No changes | `WakeWordEngine.java`, `ProfileStore.java`, `IrisListeningService.java`, `SecureStore.java` |

## Version

- versionName: 0.4.0 (small feature — redesigned training UX)
- versionCode: 179
- okhttp: not applicable (no dependency)
