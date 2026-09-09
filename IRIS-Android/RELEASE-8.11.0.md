# IRIS 8.11.0 — Phase 5 app integrations

Based on main 10e134b (8.10.0), preserving its voice, planner, memory, capture and media fixes.

## Added

- Validated app-search and reviewed-sharing adapters using Android intents. Supported app names: WhatsApp, Telegram, Spotify, YouTube, YouTube Music, Maps, Chrome, Gmail. Each action checks whether the installed app actually supports it; not every app supports every action.
- Local planner tools `search_app`, `share_text_to_app`, `share_recent_media`. App plans must be confident, complete and contain exactly one supported step with validated arguments. Arbitrary packages, file paths and recipient arguments are rejected.
- Explicit notification reply screen: unlock, select an active replyable conversation, type, review and send. The live notification/action is checked again before submission. No model can press Send. Removed/updated notifications require reselection.
- App handoffs report completion as unverified; the existing share sheet no longer records a delivered message.
- In-app guide and feature deck examples; regression checks for payload preservation and rejected plans.

## Try it

1. Say “search Arijit Singh on Spotify”. If that installed version does not expose Android search, IRIS explains the limitation instead of choosing another app.
2. Say “find coffee near me in Maps”. The destination is encoded as a map query.
3. Say “share the latest video via WhatsApp”. Review the selected accessible file and choose the recipient inside WhatsApp. Recent-media selection retains IRIS's existing preference for IRIS-created files.
4. Say “share via Telegram saying I will be late”. The message body is preserved.
5. Enable notification access and receive a replyable message. Say “reply to a notification”. Choose it, enter a reply and review before sending. Cancel/lock/remove the notification to verify no reply occurs.

## Boundaries and validation

No paid API or new network client is introduced. Target apps may use their own network services; this is not a claim that all existing IRIS features are offline. Existing calls, SMS, email, calendar, Maps navigation and media-session handlers remain available. No unsupported PULSE protocol or autonomous Accessibility clicking is invented. Notification replies currently use typed review, not voice dictation.

Run `bash IRIS-Android/scripts/test-speech.sh`. Pure Java checks and Java syntax parsing passed locally. APK build was attempted but the workspace cannot download Gradle (network unreachable); Android type checking and device acceptance remain pending.

The credential-bearing source archive and existing profile-bundling workflows are not republished or triggered in this change. Earlier automatic approval review rejected publishing those artifacts. This PR contains the source changes only.

Reference: https://developer.android.com/guide/components/intents-common
Reference: https://developer.android.com/training/sharing/send
