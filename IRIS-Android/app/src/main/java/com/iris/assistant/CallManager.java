package com.iris.assistant;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manages telephony, contact resolution, disambiguation, call confirmations, and redial flows.
 */
public class CallManager {

    public static final String CALL_CHANNEL = "iris_calls";
    public static final int CALL_NOTIFICATION = 1;
    public static final int TEACH_NOTIFICATION = 2;

    public static class ContactMatch {
        public final String name;
        public final String number;
        public final double score;

        public ContactMatch(String name, String number, double score) {
            this.name = name;
            this.number = number;
            this.score = score;
        }
    }

    public interface Host {
        void speak(String text);
        void speakThenRun(String text, Runnable after);
        void broadcastMessage(String text);
        void broadcastMessageEphemeral(String text);
        void startConfirmationRecognition();
        void startCommandRecognition();
        void rearmAfterAction();
        void destroyRecognizer();
        boolean hasPermission(String permission);
        String personalityLine(String key, String arg);
        ActionLedger ledger();
        boolean isRunning();
        String getPhase();
        void beginSms(String name, String body);
        void onCallPlacedSuccessfully();
        void onCallFailed(Throwable error);
        void speakConfirmationPrompt(String confirmText, Runnable onDone);
    }

    private final Context context;
    private final Handler handler;
    private final AppSettings settings;
    private final Host host;

    private String pendingName;
    private String pendingNumber;
    private List<ContactMatch> pendingCandidates;
    private int pendingCandidateIndex = 0;
    private int confirmationRetries = 0;
    private Runnable confirmTimeout;

    private static final Map<String, Integer> DIGIT_WORDS = new HashMap<>();
    static {
        String[] ones = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"};
        for (int i = 0; i < ones.length; i++) DIGIT_WORDS.put(ones[i], i);
    }

    public CallManager(Context context, Handler handler, AppSettings settings, Host host) {
        this.context = context.getApplicationContext();
        this.handler = handler != null ? handler : new Handler(Looper.getMainLooper());
        this.settings = settings != null ? settings : new AppSettings(context);
        this.host = host;
    }

    public String getPendingName() {
        return pendingName;
    }

    public void setPendingName(String name) {
        this.pendingName = name;
    }

    public String getPendingNumber() {
        return pendingNumber;
    }

    public void setPendingNumber(String number) {
        this.pendingNumber = number;
    }

    public List<ContactMatch> getPendingCandidates() {
        return pendingCandidates;
    }

    public void setPendingCandidates(List<ContactMatch> candidates) {
        this.pendingCandidates = candidates;
        this.pendingCandidateIndex = 0;
    }

    public int getPendingCandidateIndex() {
        return pendingCandidateIndex;
    }

    public void clearPending() {
        pendingName = null;
        pendingNumber = null;
        pendingCandidates = null;
        pendingCandidateIndex = 0;
        cancelConfirmTimeout();
    }

    public void cancelConfirmTimeout() {
        if (confirmTimeout != null) {
            handler.removeCallbacks(confirmTimeout);
            confirmTimeout = null;
        }
    }

    public void armConfirmTimeout() {
        cancelConfirmTimeout();
        confirmTimeout = () -> {
            if (host != null && host.isRunning() && "confirm".equals(host.getPhase())) {
                host.broadcastMessage("No answer. Call cancelled.");
                LogStore.append(context, "TIMEOUT", "Confirmation expired for " + (pendingName == null ? "contact" : pendingName));
                cancelCallNotification();
                host.rearmAfterAction();
            }
        };
        handler.postDelayed(confirmTimeout, 15_000);
    }

    public void handleCallContact(String name) {
        if (name == null || name.trim().isEmpty()) {
            host.broadcastMessage("Who would you like me to call?");
            host.speakThenRun("Who would you like me to call?", host::startCommandRecognition);
            LogStore.append(context, "CALL", "No name given");
            return;
        }
        String requested = name.trim();
        if (requested.matches("(?i)^(?:me|myself|my\\s*self|my\\s+phone|my\\s+number)$")) {
            host.broadcastMessage("That's you — I can't call you. Tell me who to call.");
            host.speakThenRun("That's you! Who would you like me to call?", host::rearmAfterAction);
            LogStore.append(context, "CALL", "Refused self-call");
            return;
        }
        String dialNumber = extractPhoneNumber(requested);
        if (dialNumber != null) {
            pendingCandidates = null;
            requestCallConfirmation(dialNumber, dialNumber, "Call " + speakableNumber(dialNumber) + "?");
            return;
        }
        if (requested.length() < 2) {
            host.broadcastMessage("Who would you like me to call?");
            host.speakThenRun("Who would you like me to call?", host::startCommandRecognition);
            LogStore.append(context, "CALL", "No name given");
            return;
        }
        List<ContactMatch> candidates = resolveContacts(requested);
        if (candidates.isEmpty()) {
            host.broadcastMessage(host.hasPermission(Manifest.permission.READ_CONTACTS)
                    ? "I couldn't confidently match “" + requested + "”. Teach me once."
                    : "Contacts permission is needed to find “" + requested + "”.");
            LogStore.append(context, "NO MATCH", requested);
            requestCorrection(requested);
            host.rearmAfterAction();
            return;
        }
        pendingCandidates = candidates;
        pendingCandidateIndex = 0;
        confirmCandidate(0);
    }

    public void confirmCandidate(int index) {
        if (pendingCandidates == null || index >= pendingCandidates.size()) {
            cancelCallNotification();
            host.broadcastMessage("Okay, I won't call anyone.");
            host.speakThenRun("Okay, I won't call anyone.", host::rearmAfterAction);
            pendingCandidates = null;
            return;
        }
        pendingCandidateIndex = index;
        ContactMatch c = pendingCandidates.get(index);
        boolean multiple = pendingCandidates.size() > 1;
        String prompt = multiple ? "Did you mean " + c.name + "?" : "Call " + c.name + "?";
        requestCallConfirmation(c.name, c.number, prompt);
    }

    public void handleRedial() {
        ProfileStore store = new ProfileStore(context);
        ProfileStore.Entry last = store.lastCalled();
        if (last == null || last.phoneNumber == null || last.phoneNumber.trim().isEmpty() || last.lastCalled == 0) {
            host.broadcastMessage("I don’t have anyone to call back yet.");
            LogStore.append(context, "REDIAL", "No recent calls");
            host.rearmAfterAction();
            return;
        }
        LogStore.append(context, "REDIAL", last.contactName);
        requestCallConfirmation(last.contactName, last.phoneNumber);
    }

    public void handleReferent(Referent referent, ProfileStore store) {
        ProfileStore.Entry last = store.lastCalled();
        if (last == null || last.phoneNumber == null || last.phoneNumber.trim().isEmpty()) {
            host.broadcastMessage("I’m not sure who you mean yet. Say the name once and I’ll remember it.");
            LogStore.append(context, "REFERENT", "No recent contact to resolve");
            host.rearmAfterAction();
            return;
        }
        LogStore.append(context, "REFERENT", referent.action + " → " + last.contactName);
        if (referent.action == Referent.Action.TEXT) {
            host.beginSms(last.contactName, null);
        } else {
            requestCallConfirmation(last.contactName, last.phoneNumber);
        }
    }

    public void handleHistory(String normalized, ProfileStore store) {
        if (normalized.matches(".*\\bwho\\s+did\\s+i\\s+call\\s+last\\b.*")) {
            ProfileStore.Entry last = store.lastCalled();
            if (last != null) {
                String text = "Your last call was to " + last.contactName;
                host.speak(text);
                host.broadcastMessage(text);
            } else {
                host.broadcastMessage("I don’t have any call history yet.");
            }
        } else if (normalized.matches(".*\\bwho\\s+did\\s+i\\s+call\\s+today\\b.*") || normalized.matches(".*\\bmy\\s+calls\\b.*") || normalized.matches(".*\\bcall\\s+history\\b.*")) {
            List<ProfileStore.Entry> today = store.calledToday();
            if (today.isEmpty()) {
                host.broadcastMessage("No calls yet today.");
            } else {
                List<String> names = new ArrayList<>();
                for (ProfileStore.Entry e : today) names.add(e.contactName);
                String text = "Today you called: " + String.join(", ", names);
                host.speak(text);
                host.broadcastMessage(text);
            }
        } else if (normalized.matches(".*\\bhow\\s+many\\s+times\\b.*")) {
            String who = normalized.replaceAll(".*how\\s+many\\s+times\\s+did\\s+i\\s+call\\s+", "").trim();
            List<ContactMatch> matches = resolveContacts(who);
            if (!matches.isEmpty()) {
                ContactMatch best = matches.get(0);
                ProfileStore.Entry entry = null;
                for (ProfileStore.Entry e : store.getEntries()) {
                    if (e.contactName.equalsIgnoreCase(best.name)) { entry = e; break; }
                }
                if (entry != null && entry.callCount > 0) {
                    String text = "You’ve called " + entry.contactName + " " + entry.callCount + " time" + (entry.callCount != 1 ? "s" : "") + " total.";
                    host.speak(text);
                    host.broadcastMessage(text);
                } else {
                    host.broadcastMessage("I don’t have call records for " + who + ".");
                }
            } else {
                host.broadcastMessage("I couldn’t find a contact matching “" + who + "”.");
            }
        } else if (normalized.matches(".*\\bwhen\\s+did\\s+i\\b.*")) {
            String who = normalized.replaceAll(".*when\\s+did\\s+i\\s+(?:last\\s+)?call\\s+", "").trim();
            List<ContactMatch> matches = resolveContacts(who);
            if (!matches.isEmpty()) {
                ContactMatch best = matches.get(0);
                ProfileStore.Entry entry = null;
                for (ProfileStore.Entry e : store.getEntries()) {
                    if (e.contactName.equalsIgnoreCase(best.name)) { entry = e; break; }
                }
                if (entry != null && entry.lastCalled > 0) {
                    String timeAgo = relativeTime(entry.lastCalled);
                    String text = "You last called " + entry.contactName + " " + timeAgo + ".";
                    host.speak(text);
                    host.broadcastMessage(text);
                } else {
                    host.broadcastMessage("I don’t have a record of calling " + who + ".");
                }
            } else {
                host.broadcastMessage("I couldn’t find a contact matching “" + who + "”.");
            }
        } else {
            host.broadcastMessage("I didn’t understand the history question.");
        }
        LogStore.append(context, "HISTORY", normalized);
        host.rearmAfterAction();
    }

    public static String relativeTime(long timestamp) {
        long diff = System.currentTimeMillis() - timestamp;
        long minutes = diff / 60_000;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " minute" + (minutes != 1 ? "s" : "") + " ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + " hour" + (hours != 1 ? "s" : "") + " ago";
        long days = hours / 24;
        if (days == 1) return "yesterday";
        return days + " days ago";
    }

    public void requestCallConfirmation(String name, String number) {
        requestCallConfirmation(name, number, null);
    }

    public void requestCallConfirmation(String name, String number, String customPrompt) {
        if (name == null || number == null) {
            host.rearmAfterAction();
            return;
        }
        String warning = callTimingWarning(name, number);
        if (warning != null) {
            host.broadcastMessage(warning);
            host.speak(warning);
        }
        pendingName = name;
        pendingNumber = number;
        confirmationRetries = 0;
        host.destroyRecognizer();
        LogStore.append(context, "CONFIRM", "Waiting to call " + name);
        broadcastCallPrompt(name, number);
        showCallNotification(name, number);
        String confirmText = customPrompt != null ? customPrompt : host.personalityLine("confirm", name);
        host.speakConfirmationPrompt(confirmText, host::startConfirmationRecognition);
    }

    public void handleConfirmation(String heard) {
        String answer = ProfileStore.normalize(heard);
        LogStore.append(context, "CONFIRM VOICE", answer);
        if (answer.matches("^(?:no|nope|nah|nahi|cancel|wrong|not\\s+\\w+|stop|kill|abort|forget\\s*it|leave\\s*it|hang\\s*up|don'?t|end\\s*it|nvm|never\\s*mind|ruk|bas|chhod)\\b.*")) {
            cancelConfirmTimeout();
            cancelCallNotification();
            if (pendingCandidates != null && pendingCandidateIndex + 1 < pendingCandidates.size()) {
                int next = pendingCandidateIndex + 1;
                LogStore.append(context, "CONFIRM", "Declined, trying next: " + pendingCandidates.get(next).name);
                handler.postDelayed(() -> confirmCandidate(next), 300);
            } else {
                pendingCandidates = null;
                host.broadcastMessage("Call cancelled.");
                host.speakThenRun("Okay, cancelled.", host::rearmAfterAction);
            }
        } else if (answer.matches("^(?:yes|yeah|yep|yup|confirm|correct|right|sure|okay|ok|do\\s+it|go\\s+ahead|haan|ha)\\b.*")
                || answer.matches("^(?:call|dial|ring)(?:\\s+(?:him|her|them|it|now|please|karo))?$")) {
            cancelConfirmTimeout();
            pendingCandidates = null;
            placeCall(pendingName, pendingNumber);
        } else if (confirmationRetries++ < 1) {
            host.broadcastMessage("Say “yes” to call or “no” to cancel.");
            host.speakThenRun("Say yes to call, or no to cancel.", host::startConfirmationRecognition);
        } else {
            cancelCallNotification();
            host.broadcastMessage("I couldn't understand. Call cancelled.");
            LogStore.append(context, "CANCELLED", "Unrecognized confirmation for " + (pendingName == null ? "contact" : pendingName));
            host.rearmAfterAction();
        }
    }

    public void placeCall(String name, String number) {
        if (number == null || number.trim().isEmpty()) {
            host.broadcastMessage("That contact has no callable number.");
            host.rearmAfterAction();
            return;
        }
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        if (settings.requireUnlock() && keyguard != null && keyguard.isDeviceLocked()) {
            cancelCallNotification();
            requestUnlockForCall(name, number);
            return;
        }
        pendingName = name;
        pendingNumber = number;
        host.speak(host.personalityLine("calling", name));
        host.broadcastMessage("Calling " + name + "…");
        handler.postDelayed(() -> performCall(name, number), settings.voiceReplies() ? 500 : 80);
    }

    public void performCall(String name, String number) {
        String digits = number.replaceAll("[^0-9+]", "");
        boolean sensitive = digits.replace("+", "").length() <= 3 || digits.startsWith("1900");
        boolean direct = host.hasPermission(Manifest.permission.CALL_PHONE) && !sensitive;
        Intent call = new Intent(direct ? Intent.ACTION_CALL : Intent.ACTION_DIAL,
                Uri.parse("tel:" + Uri.encode(digits))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            new ProfileStore(context).recordCall(name, number);
            BehaviorAnalyzer.onCallPlaced(context, name, number);
            try {
                if (host.ledger() != null) {
                    host.ledger().record("call", ActionLedger.OK,
                            "Called " + (name == null ? number : name), "", "", false);
                }
            } catch (Throwable ignored) { }
            LogStore.append(context, direct ? "CALLING" : "DIALER", name == null ? number : name);

            boolean launched = false;
            try {
                context.startActivity(call);
                launched = true;
            } catch (Throwable t) {
                LogStore.append(context, "CALL", "direct start blocked: " + t);
            }

            if (!launched) {
                try {
                    PendingIntent pi = PendingIntent.getActivity(context, 51, call,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    Notification n = new Notification.Builder(context, CALL_CHANNEL)
                            .setSmallIcon(R.drawable.ic_iris)
                            .setContentTitle("Calling " + (name == null ? number : name))
                            .setContentText("Tap if the call doesn't start")
                            .setCategory(Notification.CATEGORY_CALL)
                            .setFullScreenIntent(pi, true)
                            .setAutoCancel(true).build();
                    NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) nm.notify(0xCA11, n);
                    launched = true;
                } catch (Throwable t) {
                    LogStore.append(context, "CALL", "full-screen fallback failed: " + t);
                }
            }

            if (!launched) {
                host.broadcastMessage("This device blocked the call. Tap the notification to dial.");
                final String who = (name != null && !name.trim().isEmpty()) ? name.trim()
                        : (number != null && !number.trim().isEmpty() ? number.trim() : null);
                if (who != null) {
                    host.speakThenRun("I couldn't start the call. I'll text " + who
                            + " instead — say cancel if you'd rather not.",
                            () -> host.beginSms(who, null));
                } else {
                    host.speakThenRun("I couldn't start the call.", host::rearmAfterAction);
                }
                return;
            }

            host.onCallPlacedSuccessfully();
        } catch (Exception error) {
            LogStore.append(context, "ERROR", "Call failed: " + error.getMessage());
            host.broadcastMessage("This device could not place the call.");
            host.onCallFailed(error);
        }
    }

    public void showCallNotification(String name, String number) {
        Intent confirm = new Intent(context, IrisListeningService.class).setAction(IrisListeningService.ACTION_CONFIRM_CALL)
                .putExtra(IrisListeningService.EXTRA_NAME, name).putExtra(IrisListeningService.EXTRA_NUMBER, number);
        Intent cancel = new Intent(context, IrisListeningService.class).setAction(IrisListeningService.ACTION_CANCEL_CALL)
                .putExtra(IrisListeningService.EXTRA_NAME, name);
        PendingIntent yes = PendingIntent.getService(context, 2, confirm,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent no = PendingIntent.getService(context, 3, cancel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(context, CALL_CHANNEL)
                .setSmallIcon(R.drawable.ic_iris).setContentTitle("Call " + name + "?")
                .setContentText(number + " • Say Call or Cancel")
                .setContentIntent(PendingIntent.getActivity(context, 4,
                        new Intent(context, MainActivity.class).putExtra(IrisListeningService.EXTRA_NAME, name).putExtra(IrisListeningService.EXTRA_NUMBER, number),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(new Notification.Action.Builder(null, "Cancel", no).build())
                .addAction(new Notification.Action.Builder(null, "Call", yes).build())
                .setVisibility(Notification.VISIBILITY_PRIVATE).build();
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(CALL_NOTIFICATION, notification);
    }

    public void cancelCallNotification() {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(CALL_NOTIFICATION);
    }

    public void requestDisambiguation(List<ContactMatch> candidates) {
        host.destroyRecognizer();
        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> numbers = new ArrayList<>();
        for (ContactMatch candidate : candidates) {
            names.add(candidate.name);
            numbers.add(candidate.number);
        }
        context.sendBroadcast(new Intent(IrisListeningService.EVENT_DISAMBIGUATE).setPackage(context.getPackageName())
                .putStringArrayListExtra(IrisListeningService.EXTRA_NAMES, names)
                .putStringArrayListExtra(IrisListeningService.EXTRA_NUMBERS, numbers));
        Intent open = new Intent(context, MainActivity.class)
                .putStringArrayListExtra(IrisListeningService.EXTRA_NAMES, names)
                .putStringArrayListExtra(IrisListeningService.EXTRA_NUMBERS, numbers);
        PendingIntent content = PendingIntent.getActivity(context, 14, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(context, CALL_CHANNEL)
                .setSmallIcon(R.drawable.ic_iris)
                .setContentTitle("Which contact did you mean?")
                .setContentText(String.join(" • ", names))
                .setContentIntent(content)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(CALL_NOTIFICATION, notification);
        handler.postDelayed(() -> {
            if (host.isRunning() && pendingName == null && "command".equals(host.getPhase())) {
                host.broadcastMessage("No selection made. Going back to sleep.");
                LogStore.append(context, "TIMEOUT", "Disambiguation expired");
                cancelCallNotification();
                host.rearmAfterAction();
            }
        }, 30_000);
    }

    public void requestCorrection(String heard) {
        context.sendBroadcast(new Intent(IrisListeningService.EVENT_TEACH).setPackage(context.getPackageName())
                .putExtra(IrisListeningService.EXTRA_TEXT, heard));
        Intent open = new Intent(context, MainActivity.class)
                .putExtra(IrisListeningService.EXTRA_TEACH, true).putExtra(IrisListeningService.EXTRA_TEXT, heard);
        Notification notification = new Notification.Builder(context, CALL_CHANNEL)
                .setSmallIcon(R.drawable.ic_iris)
                .setContentTitle("Teach IRIS what you meant?")
                .setContentText("“" + heard + "”")
                .setContentIntent(PendingIntent.getActivity(context, 34, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true).build();
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(TEACH_NOTIFICATION, notification);
    }

    public void requestUnlockForCall(String name, String number) {
        Intent open = new Intent(context, MainActivity.class)
                .putExtra(IrisListeningService.EXTRA_NAME, name).putExtra(IrisListeningService.EXTRA_NUMBER, number)
                .putExtra(IrisListeningService.EXTRA_AUTH_REQUIRED, true);
        PendingIntent content = PendingIntent.getActivity(context, 24, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(context, CALL_CHANNEL)
                .setSmallIcon(R.drawable.ic_iris)
                .setContentTitle("Unlock to call " + name)
                .setContentText("IRIS protects calls while your phone is locked.")
                .setContentIntent(content).setAutoCancel(true).build();
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(CALL_NOTIFICATION, notification);
        LogStore.append(context, "LOCKED", "Authentication required for " + name);
    }

    public void broadcastCallPrompt(String name, String number) {
        context.sendBroadcast(new Intent(IrisListeningService.EVENT_CALL_PROMPT).setPackage(context.getPackageName())
                .putExtra(IrisListeningService.EXTRA_NAME, name).putExtra(IrisListeningService.EXTRA_NUMBER, number));
    }

    public String callTimingWarning(String name, String number) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 23 || hour < 6) {
            return "It’s late. Are you sure about calling " + name + "?";
        }
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL) {
                return "Do Not Disturb is on. Still want to call " + name + "?";
            }
        } catch (Exception ignored) { }
        String noCallsAfter = MemoryStore.findPreference(context, "no calls after");
        if (noCallsAfter != null) {
            try {
                int cutoff = Integer.parseInt(noCallsAfter.replaceAll("[^0-9]", ""));
                if (cutoff > 0 && cutoff <= 24 && hour >= cutoff) {
                    return "Your rule says no calls after " + cutoff + ". Override for " + name + "?";
                }
            } catch (Exception ignored) { }
        }
        ProfileStore store = new ProfileStore(context);
        for (ProfileStore.Entry entry : store.getEntries()) {
            if (entry.phoneNumber != null && entry.phoneNumber.replaceAll("[^0-9+]", "").equals(
                    number.replaceAll("[^0-9+]", "")) && entry.callCount >= 3) {
                long hoursSinceLast = (System.currentTimeMillis() - entry.lastCalled) / 3_600_000;
                if (hoursSinceLast < 2) {
                    return "You’ve called " + name + " " + entry.callCount + " times recently. Try again?";
                }
            }
        }
        return null;
    }

    public List<ContactMatch> resolveContacts(String requested) {
        String target = ProfileStore.normalize(requested);
        if (target.matches("[0-9 ]{3,}")) {
            return Collections.singletonList(new ContactMatch(requested, requested.replace(" ", ""), 1.0));
        }
        List<ContactMatch> matches = new ArrayList<>();
        if (!host.hasPermission(Manifest.permission.READ_CONTACTS)) return matches;
        String[] projection = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER};
        try (Cursor cursor = context.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null, null)) {
            if (cursor == null) return matches;
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                String number = cursor.getString(1);
                double score = nameScore(target, ProfileStore.normalize(name));
                if (score >= .72) matches.add(new ContactMatch(name, number, score));
            }
        } catch (Exception error) {
            LogStore.append(context, "ERROR", "Contact lookup failed: " + error.getMessage());
        }
        matches.sort((a, b) -> Double.compare(b.score, a.score));
        return matches;
    }

    public double nameScore(String target, String candidate) {
        if (target.equals(candidate)) return 1;
        if (candidate.startsWith(target) || target.startsWith(candidate)) return .90;
        if (candidate.contains(target) || target.contains(candidate)) return .82;
        for (String a : target.split(" ")) for (String b : candidate.split(" "))
            if (a.length() > 2 && a.equals(b)) return .76;
        if (soundex(target).equals(soundex(candidate)) && !soundex(target).isEmpty()) return .74;
        return textSimilarity(target, candidate);
    }

    public static String soundex(String value) {
        String latin = value.replaceAll("[^a-z]", "");
        if (latin.isEmpty()) return "";
        String map = "01230120022455012623010202";
        StringBuilder result = new StringBuilder().append(latin.charAt(0));
        char previous = map.charAt(latin.charAt(0) - 'a');
        for (int i = 1; i < latin.length() && result.length() < 4; i++) {
            char code = map.charAt(latin.charAt(i) - 'a');
            if (code != '0' && code != previous) result.append(code);
            previous = code;
        }
        while (result.length() < 4) result.append('0');
        return result.toString();
    }

    public static double textSimilarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous; previous = current; current = swap;
        }
        return 1.0 - ((double) previous[b.length()] / Math.max(a.length(), b.length()));
    }

    public static String extractPhoneNumber(String text) {
        if (text == null) return null;
        String t = text.toLowerCase(Locale.ROOT).trim();
        String[] toks = t.split("[^a-z0-9+]+");
        StringBuilder digits = new StringBuilder();
        int repeat = 1;
        for (String tok : toks) {
            if (tok.isEmpty()) continue;
            if (tok.equals("double")) { repeat = 2; continue; }
            if (tok.equals("triple")) { repeat = 3; continue; }
            if (tok.equals("plus")) { digits.append('+'); repeat = 1; continue; }
            String d = null;
            if (tok.matches("[0-9]+")) d = tok;
            else if (tok.equals("oh") || tok.equals("o") || tok.equals("zero")) d = "0";
            else if (DIGIT_WORDS.containsKey(tok)) d = String.valueOf(DIGIT_WORDS.get(tok));
            if (d != null) { for (int i = 0; i < repeat; i++) digits.append(d); }
            repeat = 1;
        }
        String justDigits = digits.toString().replaceAll("[^0-9]", "");
        return justDigits.length() >= 7 ? digits.toString() : null;
    }

    public static String speakableNumber(String number) {
        if (number == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : number.toCharArray()) {
            if (Character.isDigit(c)) sb.append(c).append(' ');
            else if (c == '+') sb.append("plus ");
        }
        return sb.toString().trim();
    }
}
