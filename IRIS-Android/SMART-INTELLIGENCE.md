# IRIS Smart Intelligence — v1.1.0

## Expanded Command Patterns

### Current (rigid)
```regex
^(?:please\s+)?(?:call|dial|phone|ring)\s+(.+?)(?:\s+please)?$
```

### New (flexible)
```
call X, dial X, phone X, ring X
ring up X, phone up X
get X on the line, get X on the phone
talk to X, speak to X, connect me to X
reach X, buzz X, hit up X
X ko call karo, X se baat karo, X ko phone karo
redial, call again, call the last person, call back
what time is it, time please, battery, stop, shut up, quiet
```

## Relationship Nicknames

Users can train relationship labels:
- "wife" → Priya (mapped to contact)
- "brother" → Rahul
- "office" → 011-12345678
- "mom" → already works via contact name

Stored in ProfileStore as `relationships` map.

## Context Commands

- "Redial" / "Call again" → last called contact from ProfileStore
- "Call back" → same as redial

## Quick Actions (non-call intents)

- "What time is it" / "Time" → TTS speaks current time
- "Battery" → TTS speaks battery percentage
- "Stop" / "Shut up" / "Quiet" / "Cancel" → stops listening
- "Help" → lists what IRIS can do

## Smart Response System

- Time-aware greetings when wake phrase detected
- Helpful suggestions on no match
- Personality-driven responses for quick actions

## Files to Change

| File | Change |
|------|--------|
| IrisListeningService.java | New command pattern, intent routing, quick actions, redial |
| ProfileStore.java | Relationship storage, last-called retrieval |
| AppSettings.java | No changes needed |
| MainActivity.java | Relationship training UI in profile details |

## Version
- versionName: 1.1.0 (small feature)
- versionCode: 182
