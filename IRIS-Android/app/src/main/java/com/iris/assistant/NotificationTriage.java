package com.iris.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A triage digest of waiting notifications: who actually needs the owner, what's urgent, and what
 * can wait \u2014 instead of a flat count. Pure and offline-testable; it reuses the existing pure
 * classifiers (channel, urgency, SMS-shortcode) so no Android types are needed.
 *
 * The judgement:
 *   - A real PERSON is a messaging-channel notification (WhatsApp/SMS/\u2026) with a sender that
 *     isn't an SMS shortcode/sender-ID. Those are the ones who might be waiting on a reply.
 *   - A person's message is URGENT if it classifies as urgent or reads as distressed.
 *   - Everything else (promos, app alerts, shortcodes) is "app alerts" that can wait.
 */
public final class NotificationTriage {

    private NotificationTriage() { }

    /** One captured notification, with enough to triage it. */
    public static final class Item {
        public final String app;
        public final String sender;
        public final String text;
        public final String pkg;

        public Item(String app, String sender, String text, String pkg) {
            this.app = app == null ? "" : app.trim();
            this.sender = sender == null ? "" : sender.trim();
            this.text = text == null ? "" : text;
            this.pkg = pkg == null ? "" : pkg;
        }
    }

    public static String summarize(List<Item> items) {
        if (items == null || items.isEmpty()) return "Nothing waiting.";

        Map<String, Integer> people = new LinkedHashMap<>();   // sender -> message count
        Set<String> urgent = new LinkedHashSet<>();            // senders who look urgent
        Map<String, Integer> apps = new LinkedHashMap<>();     // app -> count (non-person tail)
        int personTotal = 0;
        int total = items.size();
        String firstMedia = "";                                // label of the first media item

        for (Item it : items) {
            if (it == null) continue;
            MessagingApp ch = MessagingApp.fromPackage(it.pkg);
            boolean person = ch != MessagingApp.OTHER
                    && !it.sender.isEmpty()
                    && !(ch.isSms() && looksLikeSenderId(it.sender));
            if (person) {
                people.merge(it.sender, 1, Integer::sum);
                personTotal++;
                if (looksUrgent(it.text)) urgent.add(it.sender);
                if (firstMedia.isEmpty()) {
                    String media = MediaType.label(MediaType.fromText(it.text));
                    if (!media.isEmpty()) firstMedia = media;
                }
            } else {
                String app = it.app.isEmpty() ? "another app" : it.app;
                apps.merge(app, 1, Integer::sum);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(total).append(total == 1 ? " notification. " : " notifications. ");

        if (!urgent.isEmpty()) {
            sb.append(urgent.size() == 1 ? "One looks urgent" : urgent.size() + " look urgent")
              .append(" \u2014 from ").append(joinNames(urgent)).append(". ");
        }

        if (!people.isEmpty()) {
            int ppl = people.size();
            sb.append(personTotal).append(personTotal == 1 ? " message from " : " messages from ")
              .append(ppl).append(ppl == 1 ? " person" : " people");
            String top = null;
            int topCount = 0;
            for (Map.Entry<String, Integer> e : people.entrySet()) {
                if (e.getValue() > topCount) { topCount = e.getValue(); top = e.getKey(); }
            }
            if (ppl > 1 && topCount > 1) {
                sb.append(", most from ").append(top).append(" (").append(topCount).append(")");
            }
            sb.append(". ");
            if (!firstMedia.isEmpty()) sb.append("One sent ").append(firstMedia).append(". ");
        }

        int nonPerson = total - personTotal;
        if (nonPerson > 0) {
            if (!people.isEmpty()) {
                sb.append("Plus ").append(nonPerson)
                  .append(nonPerson == 1 ? " app alert that can wait." : " app alerts that can wait.");
            } else {
                sb.append(appTail(apps));
            }
        } else if (!urgent.isEmpty()) {
            sb.append("The rest can wait.");
        }

        return sb.toString().trim();
    }

    /**
     * An SMS sender-ID / shortcode rather than a saved person's name. A real contact name is
     * title-case and often spaced ("Mom", "Boss", "Ravi K"); a sender ID is a short, spaceless,
     * all-caps / hyphenated / digit-bearing token ("VM-HDFCBK", "AMAZON", "AX-SBIINB").
     */
    private static boolean looksLikeSenderId(String s) {
        if (s == null || s.isEmpty() || s.contains(" ") || s.length() > 12) return false;
        boolean hasDigit = false;
        boolean hasLetter = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) hasDigit = true;
            if (Character.isLetter(c)) hasLetter = true;
        }
        boolean hasHyphen = s.indexOf('-') >= 0;
        boolean allUpper = hasLetter && s.equals(s.toUpperCase(java.util.Locale.ROOT));
        return hasHyphen || hasDigit || allUpper;
    }

    private static boolean looksUrgent(String text) {
        if (text == null || text.isEmpty()) return false;
        MessageCategory.Result c = MessageCategory.classify(text);
        if (c.kind == MessageCategory.Kind.URGENT) return true;
        return SentimentScore.score(text).distressed();
    }

    /** Up to three names, comma/and joined. */
    private static String joinNames(Set<String> names) {
        List<String> list = new ArrayList<>(names);
        int show = Math.min(3, list.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < show; i++) {
            if (i > 0) sb.append(i == show - 1 && list.size() <= 3 ? " and " : ", ");
            sb.append(list.get(i));
        }
        if (list.size() > 3) sb.append(" and ").append(list.size() - 3).append(" more");
        return sb.toString();
    }

    /** "2 from WhatsApp, 1 from Gmail." for the no-person case. */
    private static String appTail(Map<String, Integer> apps) {
        List<Map.Entry<String, Integer>> ordered = new ArrayList<>(apps.entrySet());
        ordered.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        int named = Math.min(4, ordered.size());
        for (int i = 0; i < named; i++) {
            if (i > 0) sb.append(i == named - 1 && ordered.size() <= 4 ? " and " : ", ");
            sb.append(ordered.get(i).getValue()).append(" from ").append(ordered.get(i).getKey());
        }
        sb.append('.');
        return sb.toString();
    }
}
