# IRIS — UI / UX Design Specification

**Goal:** A fast, good-looking, "techy" interface with zero gimmicks. Every pixel earns its place. The app must be fully customizable and genuinely user-friendly — a power tool that a first-time user can still operate.

**Status:** Design proposal (v1). No code changed yet. Implementation follows approval.

---

## 1. Design Principles

1. **Function first.** No animation or decoration that doesn't communicate state. The orb pulses because it means "listening" — not for flair.
2. **One glance = full status.** The home screen answers "is it on? is it listening? is the AI loaded? am I private?" without scrolling.
3. **Calm by default, alive when active.** Muted surfaces at rest; accent color and motion appear only during interaction.
4. **Consistent rhythm.** A single spacing scale, one corner radius family, one type scale. Predictability *is* the techy feel.
5. **Customizable, not chaotic.** The user can retheme and re-density the whole app from one place, but sensible defaults mean they never *have* to.
6. **Accessible.** Readable contrast, large touch targets, text scaling, and screen-reader labels are requirements, not extras.

---

## 2. Design Language

### 2.1 Color system (tokenized)

Keep the existing palette but formalize it into **semantic tokens** so themes can be swapped in one place. Raw colors (violet, cyan…) are never referenced directly by layouts — only tokens are.

| Token | Default (Dark) | Role |
|---|---|---|
| `bg/base` | `#070816` (ink) | App background |
| `bg/surface` | `#0F1028` | Cards, sheets |
| `bg/surface-2` | `#181A36` | Nested cards, inputs |
| `bg/elevated` | `#1E2045` | Menus, dialogs, active tab |
| `border/subtle` | `#1E293B` | Hairlines |
| `border/accent` | `#334155` | Focused inputs |
| `text/primary` | `#F1F5F9` | Headings, values |
| `text/secondary` | `#CBD5E1` | Body |
| `text/muted` | `#64748B` | Labels, hints |
| `accent` | `#22D3EE` (cyan) | **User-selectable.** Primary actions, active states |
| `accent/dim` | derived | Pressed/track states |
| `positive` | `#34D399` (mint) | "On device", success |
| `warning` | `#FBBF24` (amber) | Downloads, attention |
| `danger` | `#FB7185` | Destructive, errors |

**Accent is a variable.** The user picks from a preset row (Cyan · Violet · Mint · Amber · Magenta · Rose) or a custom hex. Everything accent-colored updates instantly.

### 2.2 Themes (full customization)

- **Dark** (default) — the ink palette above.
- **AMOLED Black** — `bg/base = #000000`, surfaces near-black. Battery-friendly, deep contrast.
- **Light** — inverted tokens for daytime use.
- **System** — follow OS light/dark.

Each theme reads the same tokens; only the token values change. Accent color is orthogonal (works with any theme).

### 2.3 Typography

Single scale, system `sans` (no bundled fonts — keeps APK lean and renders natively "techy").

| Style | Size | Weight | Use |
|---|---|---|---|
| Display | 30sp | Bold, +0.22 letter-spacing | "IRIS" wordmark |
| Title | 20sp | Bold | Screen / big status |
| Section | 11sp | Bold, +0.08, UPPERCASE | Card headers ("SYSTEM STATUS") |
| Body | 14sp | Regular | Content |
| Label | 12sp | Medium | Field labels |
| Caption | 11sp | Regular | Hints, timestamps |
| Mono | 12sp | monospace | Activity log lines |

All sizes multiply by a user **Text Size** factor (0.85× – 1.4×).

### 2.4 Spacing, radius, elevation

- **Spacing scale:** 4 · 8 · 12 · 16 · 22 (dp). Use only these.
- **Radius:** cards 18dp · buttons 14dp · chips/pills 999dp (full) · inputs 12dp.
- **Elevation via surface color, not shadows** (shadows read poorly on dark). Higher surface token = "closer".
- **Density toggle:** Comfortable (default) vs Compact (spacing −25%, list rows shorter) for power users.

### 2.5 Motion

- Durations: 120ms (state), 220ms (view/tab change), 400ms (orb breathing loop).
- Easing: standard decelerate. No bounces, no parallax.
- **Reduce Motion** setting disables the orb animation and cross-fades tabs instantly.

---

## 3. Information Architecture

Keep the proven **5 destinations**, but clarify roles and order by frequency:

```
[ Assistant ]  [ Training ]  [ Memory ]  [ Activity ]  [ Settings ]
   home           teach        knows        history       config
```

- Bottom bar stays (thumb-reachable). Active tab: elevated surface + accent label + accent top-indicator (2dp). Inactive: muted, icon only optional in Compact.
- Content lives in the existing swappable `contentHost` — no heavy nav framework needed.

---

## 4. Screen Designs

### 4.1 Assistant (home)

The hero screen. Restructured top-to-bottom for a single-glance read.

```
┌───────────────────────────────────────────┐
│ IRIS                          • ON DEVICE  │  ← wordmark + privacy chip
│ Intelligent Responsive…                    │
├───────────────────────────────────────────┤
│                                             │
│               ╭─────────╮                   │
│               │   ORB   │   ← state-colored │
│               ╰─────────╯     breathing orb │
│               LISTENING                     │  ← state label
│                                             │
│   “How can I help?”                         │  ← last spoken / prompt
│   ┌───────────────────────────────────┐    │
│   │ ⌨  Type a command…            ➤   │    │  ← text input (always available)
│   └───────────────────────────────────┘    │
│                                             │
│   Quick actions (customizable chips)        │
│   [☎ Call] [✉ Text] [⏰ Alarm] [☀ Weather]  │
│   [🔦 Torch] [＋ Edit]                       │
│                                             │
│   ┌── SYSTEM STATUS ──────────────────┐    │
│   │ 🎙 Microphone   Phone mic          │    │
│   │ 🧠 AI brain     Ready ✓ / Download │    │
│   │ 🔊 Recognition  Indian English      │    │
│   └───────────────────────────────────┘    │
├───────────────────────────────────────────┤
│  Assistant  Training  Memory  Activity  ⚙  │
└───────────────────────────────────────────┘
```

Key changes:
- **Orb is the focal point**, color-coded by state: idle (muted), listening (accent), thinking (amber pulse), speaking (mint). One component, four meanings.
- **Always-available text input** — voice-first but not voice-only (accessibility + noisy rooms). Send arrow uses accent.
- **Customizable quick-action chips** — user pins their most-used actions; "Edit" opens a chooser. This is the "efficient, no gimmicks" core: the actions you use are one tap away.
- **System Status card** collapses to a single line when everything's healthy; expands on tap. The AI-brain row doubles as the download entry point (replaces hunting in Settings).

### 4.2 Training

Keep the 3-section card flow (it tested well) but align to the new tokens:
- Card 1: **Wake phrase** — record, quality meter, re-train.
- Card 2: **Voice samples** — count, add more, clear.
- Card 3: **Test** — "Say your wake word" live check with pass/fail chip.
- Progress uses accent; rejected samples use `warning`.

### 4.3 Memory

- Search field at top (accent focus ring).
- Grouped list: **About you · People · Preferences · Notes.**
- Each row: key (primary), value (secondary), source tag + timestamp (caption). Swipe or long-press → Edit / Delete (confirm on delete).
- FAB-less: a single "＋ Add memory" pinned button (full-width secondary) at the bottom of the list — clearer than a floating button.

### 4.4 Activity

- Monospace, reverse-chronological log lines with a colored category chip (`CALL`, `SMS`, `LLM`, `MEMORY`, `LLM ERROR` in danger).
- Filter row (All · Calls · Messages · AI · Errors).
- "Clear log" in the overflow, with confirm.

### 4.5 Settings (the customization hub)

Organized into labeled sections, each a card:

```
APPEARANCE
  Theme            ▸ Dark / AMOLED / Light / System
  Accent color     ● ● ● ● ● ●  + custom
  Text size        ├────●─────┤
  Density          ( ) Comfortable  (•) Compact
  Reduce motion    [ off ]

VOICE & LANGUAGE
  Command language ▸ System / English / Hinglish
  Voice (TTS)      ▸ pick voice + Test
  Speech engine    Indian English ✓

AI BRAIN
  Status           Ready ✓ (Qwen 2.5)
  [ Download / Re-download ]
  Hugging Face token (optional)         ▸

PERSONALITY
  Tone             ▸ Sarcastic / Warm / Professional / Silent

QUICK ACTIONS
  Edit home chips  ▸

PRIVACY & SECURITY
  Voice security   [ on ]
  Data            ▸ export / clear

ABOUT
  Version 7.1.1 · fully offline
```

Every appearance control previews live. Nothing here is buried more than one tap deep.

---

## 5. Component Library

Reusable, token-driven building blocks (each maps to a drawable + style):

- **Card** (`bg/surface`, radius 18, 16dp padding) and **Nested card** (`bg/surface-2`).
- **Primary button** (accent fill, `text/primary`), **Secondary button** (surface-2 fill, hairline border), **Text button**.
- **Chip / Pill** (full radius) — two variants: static status (mint "ON DEVICE") and tappable quick-action.
- **Section header** (uppercase caption in accent or magenta).
- **Status row** (icon · label · value/badge).
- **Input field** (surface-2, 12dp radius, accent focus border).
- **Segmented control** (density, filters).
- **Slider** (text size) and **Switch** (accent track).
- **Orb** — existing `IrisOrbView`, extended with a `state` enum {idle, listening, thinking, speaking} driving color + pulse rate.

---

## 6. Accessibility

- Minimum touch target **48×48dp** (tabs, chips, switches).
- Contrast ≥ 4.5:1 for body text on every theme (light theme tokens tuned for this).
- `contentDescription` on the orb (announces current state), all icon buttons, and status rows.
- Respects OS font scale *and* the in-app Text Size factor.
- Reduce Motion honored app-wide.
- Text input path means the assistant is usable without speaking.

---

## 7. Implementation Plan (phased)

Grounded in the current stack: `LinearLayout`/`FrameLayout` views swapped into `contentHost`, drawables in `res/drawable`, tokens in `res/values`.

**Phase 1 — Foundation (no visual regressions)**
- Introduce semantic color tokens: add `colors.xml` aliases (`accent`, `bg_base`, …) mapping to current raw colors.
- Add `themes/` variants (dark, amoled, light) as color-resource overlays selected at runtime by recreating the activity with a theme flag in `AppSettings`.
- Add Text Size + Density as `AppSettings` values already partially present (`textScale`).

**Phase 2 — Assistant home refresh**
- Restructure `view_assistant.xml`: orb hero + state label, text input row, customizable chip row, collapsible status card.
- Extend `IrisOrbView` with a `setState(...)` API; wire to the service's phase broadcasts.

**Phase 3 — Settings hub + customization**
- Rebuild `view_settings.xml` into the sectioned layout with live-preview accent picker, theme selector, sliders, segmented controls.
- Persist all appearance choices in `AppSettings`; apply on launch.

**Phase 4 — Memory / Activity / Training polish**
- Apply tokens, search, filters, confirm-dialogs, consistent rows.

**Phase 5 — Accessibility & motion pass**
- Content descriptions, target sizes, Reduce Motion, contrast audit per theme.

Each phase is independently shippable and version-bumped per the project's rules.

---

## 8. Out of Scope (explicitly, to avoid gimmicks)

- No skeuomorphic textures, glassmorphism blur, or 3D.
- No onboarding carousels or confetti.
- No bottom-sheet-everything; dialogs only for confirms and pickers.
- No bundled custom fonts (system font stays).

---

## 9. Open Questions for Approval

1. Keep **5 bottom tabs**, or fold Activity into Settings to get 4? (I recommend keeping 5.)
2. Accent presets — is the 6-color row + custom hex enough, or do you want a full color wheel?
3. Ship **Light theme** in v1, or Dark + AMOLED first and Light later?
4. Quick-action chips: fixed set vs fully user-editable (my recommendation: user-editable, default to Call/Text/Alarm/Weather/Torch)?

---

*Once you approve (or tweak) the direction, I'll implement Phase 1 + 2 first so you can see the new home screen and theming, then proceed through the phases.*
