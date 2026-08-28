package com.iris.assistant;

import android.content.Context;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Analyzes call patterns and generates auto-learned memory suggestions.
 * Runs after each call to detect routines, quiet hours, and neglected contacts.
 */
public final class BehaviorAnalyzer {

    public static class Suggestion {
        public final String emoji;
        public final String title;
        public final String category;
        public final String key;
        public final String value;

        Suggestion(String emoji, String title, String category, String key, String value) {
            this.emoji = emoji;
            this.title = title;
            this.category = category;
            this.key = key;
            this.value = value;
        }
    }

    private BehaviorAnalyzer() { }

    /**
     * Analyze call history and generate suggestions.
     * Call this from the Memory tab to show current suggestions.
     */
    public static List<Suggestion> analyze(Context context) {
        List<Suggestion> suggestions = new ArrayList<>();
        ProfileStore store = new ProfileStore(context);
        List<ProfileStore.Entry> entries = store.getEntries();

        // 1. Detect frequent callers (5+ calls)
        for (ProfileStore.Entry entry : entries) {
            if (entry.callCount >= 5) {
                String existing = MemoryStore.findPreference(context, "frequent " + entry.contactName.toLowerCase());
                if (existing == null) {
                    suggestions.add(new Suggestion("\uD83D\uDD25",
                            "You call " + entry.contactName + " often (" + entry.callCount + " times)",
                            MemoryStore.CAT_PEOPLE, "frequent contact", entry.contactName));
                }
            }
        }

        // 2. Detect neglected contacts (trained but 0 calls, or last call > 14 days)
        long twoWeeksAgo = System.currentTimeMillis() - 14L * 86_400_000L;
        for (ProfileStore.Entry entry : entries) {
            if (entry.callCount == 0) {
                suggestions.add(new Suggestion("\uD83D\uDCA4",
                        "You trained " + entry.contactName + " but never called",
                        MemoryStore.CAT_PEOPLE, "unused contact", entry.contactName));
            } else if (entry.lastCalled > 0 && entry.lastCalled < twoWeeksAgo && entry.callCount >= 2) {
                long days = (System.currentTimeMillis() - entry.lastCalled) / 86_400_000L;
                suggestions.add(new Suggestion("\u23F0",
                        "It\u2019s been " + days + " days since you called " + entry.contactName,
                        MemoryStore.CAT_PEOPLE, "reconnect", entry.contactName));
            }
        }

        // 3. Detect late-night calling pattern
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 22 || hour < 6) {
            String quietPref = MemoryStore.findPreference(context, "quiet hours");
            if (quietPref == null) {
                boolean hasLateCalls = false;
                for (ProfileStore.Entry entry : entries) {
                    if (entry.lastCalled > 0) {
                        Calendar cal = Calendar.getInstance();
                        cal.setTimeInMillis(entry.lastCalled);
                        int callHour = cal.get(Calendar.HOUR_OF_DAY);
                        if (callHour >= 22 || callHour < 6) { hasLateCalls = true; break; }
                    }
                }
                if (!hasLateCalls) {
                    suggestions.add(new Suggestion("\uD83C\uDF19",
                            "You don\u2019t usually call this late. Set quiet hours?",
                            MemoryStore.CAT_PREFERENCE, "quiet hours", "after 11 PM"));
                }
            }
        }

        // 4. Suggest saving name if not in memory
        String name = MemoryStore.ownerName(context);
        if (name == null) {
            suggestions.add(new Suggestion("\uD83D\uDC4B",
                    "Tell me your name so I can greet you personally",
                    MemoryStore.CAT_ABOUT_ME, "name", ""));
        }

        // 5. Check if voiceprint is enrolled
        ProfileStore.WakeProfile wake = store.getWakeProfile();
        if (wake.isReady() && !wake.isVoiceEnrolled()) {
            suggestions.add(new Suggestion("\uD83D\uDD12",
                    "Retrain wake phrase to enroll your voiceprint for security",
                    MemoryStore.CAT_RULE, "voiceprint", "not enrolled"));
        }

        return suggestions;
    }

    /**
     * Run after a call is placed to auto-learn patterns.
     * Called from IrisListeningService.performCall().
     */
    public static void onCallPlaced(Context context, String name, String number) {
        // Record observation in memory for pattern tracking
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String dayOfWeek = new java.text.SimpleDateFormat("EEEE", java.util.Locale.US)
                .format(new java.util.Date());

        // Check if we already have a pattern for this contact
        MemoryStore.Memory existing = MemoryStore.findByKey(context,
                MemoryStore.CAT_PEOPLE, "call pattern " + name.toLowerCase());
        if (existing == null) {
            // Create a new observation (will be promoted to pattern after enough data)
            MemoryStore.Memory obs = new MemoryStore.Memory();
            obs.category = MemoryStore.CAT_PEOPLE;
            obs.key = "call pattern " + name.toLowerCase();
            obs.value = dayOfWeek + " around " + hour + ":00";
            obs.source = "auto_learned";
            MemoryStore.add(context, obs);
        }
    }
}
