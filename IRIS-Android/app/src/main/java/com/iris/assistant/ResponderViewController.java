package com.iris.assistant;

import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Controller for the Responder section in Comms Deck.
 * Manages auto-responder rules, dry-run/live toggles, tone settings, and live decision log.
 */
public class ResponderViewController {

    private MainActivity activity;
    private View rootView;

    /** Signature of the decision log as last drawn, so polling can detect a real change. */
    private String responderLogSignature = "";
    /** Active filter for decision log: 0=ALL, 1=SPOKE, 2=SILENT, 3=DRY RUN, 4=FAILED */
    private int responderLogFilter = 0;

    public void bind(MainActivity activity, View view) {
        this.activity = activity;
        this.rootView = view;
        bindResponderSection(view);
    }

    private int dp(int value) {
        if (activity == null) return value;
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        if (activity != null) activity.toast(message);
    }

    private int getColor(int resId) {
        if (activity == null) return 0;
        return activity.getColor(resId);
    }

    private void authenticateThen(String title, Runnable action) {
        if (activity != null) {
            activity.authenticateThen(title, action);
        } else {
            action.run();
        }
    }

    private void startActivity(Intent intent) {
        if (activity != null) {
            activity.startActivity(intent);
        }
    }

    private Object getSystemService(String name) {
        if (activity == null) return null;
        return activity.getSystemService(name);
    }

    private String appLabel(String pkg) {
        if (activity == null) return pkg;
        try {
            android.content.pm.PackageManager pm = activity.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable ignored) {
            return pkg;
        }
    }

    /** Edit quiet hours. Both ends validated, and a zero-length window refused. */
    private void editQuietHours(final ResponderSettings settings, final View root) {
        final EditText from = new EditText(activity);
        from.setHint("from, e.g. 23:00");
        from.setText(ResponderWindow.formatMinute(settings.quietStart()));
        final EditText to = new EditText(activity);
        to.setHint("to, e.g. 07:00");
        to.setText(ResponderWindow.formatMinute(settings.quietEnd()));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        box.addView(from);
        box.addView(to);

        new AlertDialog.Builder(activity)
                .setTitle("When are you asleep?")
                .setMessage("24-hour times. Crossing midnight is fine \u2014 23:00 to 07:00 is "
                        + "what you'd expect it to be.")
                .setView(box)
                .setNegativeButton("Leave it", null)
                .setPositiveButton("Set", (d, w) -> {
                    int start = ResponderWindow.parseMinute(from.getText().toString());
                    int end = ResponderWindow.parseMinute(to.getText().toString());
                    if (!ResponderWindow.isValidWindow(start, end)) {
                        // Refused rather than stored. A start equal to an end would mean "always
                        // quiet", and every message would get the sleeping reply.
                        toast("That isn't a window I can use. Two different valid times, please.");
                        return;
                    }
                    settings.setQuietHours(start, end);
                    renderResponder(root);
                }).show();
    }

    /** Edit one numeric setting. */
    private void editNumber(String title, long current, final Number apply, final View root) {
        final EditText input = new EditText(activity);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(current));
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(input)
                .setNegativeButton("Leave it", null)
                .setPositiveButton("Set", (d, w) -> {
                    try {
                        apply.set(Long.parseLong(input.getText().toString().trim()));
                        renderResponder(root);
                    } catch (Exception e) {
                        toast("That wasn't a number.");
                    }
                }).show();
    }

    /** Edit the auto-disarm duration, including the indefinite option. */
    private void editDisarm(final ResponderSettings settings, final View root) {
        new AlertDialog.Builder(activity)
                .setTitle("How long should I stay armed?")
                .setMessage("This is the thing that stops me still answering for you three weeks "
                        + "from now because you forgot I was on. Everything else limits how often "
                        + "I speak; only this limits how long.")
                .setNegativeButton("Set hours", (d, w) -> {
                    settings.setArmedIndefinitely(false);
                    editNumber("Stand down after how many hours?",
                            settings.disarmAfterMs() / 3600000,
                            value -> settings.setDisarmAfterMs(value * 3600000L), root);
                })
                .setNeutralButton("Leave it", null)
                .setPositiveButton("Until I stop you", (d, w) -> {
                    settings.setArmedIndefinitely(true);
                    toast("Armed until you say otherwise. I'd rather you didn't forget.");
                    renderResponder(root);
                }).show();
    }

    /**
     * What tone is actually in force right now, when it differs from the stored default.
     *
     * The owner set SARCASTIC and kept receiving PROFESSIONAL. Both were working as written: the
     * work-hours window outranks the global default in the resolver, and that window had no UI, so
     * a rule he could not see was overruling one he had deliberately chosen. Stating the effective
     * tone and its reason is the actual fix; exposing the switch is only half of it.
     */
    private String effectiveToneNote(ResponderSettings settings) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        int minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60
                + now.get(java.util.Calendar.MINUTE);
        ReplyTone.Tone windowTone =
                ResponderWindow.toneFor(settings.toneWindows(), minute);
        if (windowTone == null || windowTone == settings.defaultTone()) return "";
        return "  \u2192 " + windowTone.name() + " now";
    }

    private String relationToneSummary(ResponderSettings settings) {
        StringBuilder sb = new StringBuilder();
        for (ReplyTone.Relation r : new ReplyTone.Relation[]{
                ReplyTone.Relation.FAMILY, ReplyTone.Relation.FRIEND, ReplyTone.Relation.WORK}) {
            String raw = settings.relationToneRaw(r);
            if (sb.length() > 0) sb.append(" \u00b7 ");
            sb.append(r.name().charAt(0)).append(r.name().substring(1).toLowerCase(java.util.Locale.ROOT))
              .append(": ").append("DEFAULT".equalsIgnoreCase(raw) ? "default" : raw.toLowerCase(java.util.Locale.ROOT));
        }
        return sb.toString();
    }

    private String nameRuleSummary(ResponderSettings settings) {
        java.util.List<ResponderSettings.NameRule> rules = settings.nameRules();
        if (rules.isEmpty()) return "none";
        return rules.size() == 1 ? rules.get(0).pattern + " \u2192 "
                + rules.get(0).actionName() : rules.size() + " rules";
    }

    /** Edit work hours, during which IRIS can be held to the professional tone. */
    private void editWorkHours(final ResponderSettings settings, final View root) {
        final EditText from = new EditText(activity);
        from.setHint("from, e.g. 09:00");
        from.setText(ResponderWindow.formatMinute(settings.workStart()));
        final EditText to = new EditText(activity);
        to.setHint("to, e.g. 18:00");
        to.setText(ResponderWindow.formatMinute(settings.workEnd()));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        box.addView(from);
        box.addView(to);

        new AlertDialog.Builder(activity)
                .setTitle("When are you working?")
                .setMessage("While this window is open I use the professional tone regardless of "
                        + "your default \u2014 which is why a sarcastic default can come out "
                        + "formal in the afternoon. Turn the switch above off if you'd rather I "
                        + "didn't.")
                .setView(box)
                .setNegativeButton("Leave it", null)
                .setPositiveButton("Set", (d, w) -> {
                    int start = ResponderWindow.parseMinute(from.getText().toString());
                    int end = ResponderWindow.parseMinute(to.getText().toString());
                    if (!ResponderWindow.isValidWindow(start, end)) {
                        toast("That isn't a window I can use. Two different valid times, please.");
                        return;
                    }
                    settings.setWorkHours(start, end);
                    renderResponder(root);
                }).show();
    }

    /**
     * Rules that match on a contact's NAME rather than on a specific conversation.
     *
     * Exists because per-contact rules are retrospective and exact: they need the person to have
     * messaged, and they bind to one name on one app. A pattern covers everyone who will ever
     * match it, which is what the owner wanted for colleagues -- one rule for anybody with "eric"
     * in their name, set once.
     */
    private void editNameRules(final ResponderSettings settings, final View root) {
        final java.util.List<ResponderSettings.NameRule> rules =
                new java.util.ArrayList<>(settings.nameRules());
        final String[] labels = new String[rules.size() + 1];
        for (int i = 0; i < rules.size(); i++) {
            ResponderSettings.NameRule r = rules.get(i);
            String tail = r.hasReply()
                    ? "say \u201C" + (r.reply.length() > 40
                            ? r.reply.substring(0, 40) + "\u2026" : r.reply) + "\u201D"
                    : r.actionName();
            labels[i] = "\"" + r.pattern + "\"  \u2192  " + tail;
        }
        labels[rules.size()] = "+ Add a rule";

        choiceDialog("Rules by name",
                "Checked in order; the first match wins. Matching is on whole words, so \"eric\" "
                        + "catches Eric and Eric Jones but never Erica, Frederica or America "
                        + "\u2014 and not Ericsson either, which needs its own rule. A relationship "
                        + "you've set on a contact still beats these.",
                labels, which -> {
                    if (which == rules.size()) {
                        editOneNameRule(settings, -1, root);
                    } else {
                        editOneNameRule(settings, which, root);
                    }
                });
    }

    private void editOneNameRule(final ResponderSettings settings, final int index,
                                 final View root) {
        final java.util.List<ResponderSettings.NameRule> rules =
                new java.util.ArrayList<>(settings.nameRules());
        final boolean adding = index < 0 || index >= rules.size();

        final EditText pattern = new EditText(activity);
        pattern.setHint("word to look for, e.g. eric");
        pattern.setMaxLines(1);
        if (!adding) pattern.setText(rules.get(index).pattern);

        // The action list is the tones, an outright refusal, and a fixed reply, all from one
        // place: a name he wants silenced, one he wants addressed formally and one he wants
        // answered with set words are the same kind of rule, keyed off the same name match.
        final ReplyTone.Tone[] tones = ReplyTone.Tone.values();
        final int neverIndex = tones.length;
        final int sayIndex = tones.length + 1;
        final String[] actions = new String[tones.length + 2];
        for (int i = 0; i < tones.length; i++) actions[i] = "Sound " + tones[i].name();
        actions[neverIndex] = "Never reply to them at all";
        actions[sayIndex] = "Say exactly this, every time";

        // The reply box, shown only when the SAY action is chosen.
        final EditText replyText = new EditText(activity);
        replyText.setHint("the exact reply, e.g. I'll call you back shortly \u2014 IRIS, Suvra's "
                + "assistant");
        replyText.setMinLines(2);
        if (!adding) replyText.setText(rules.get(index).reply);

        final int[] chosen = new int[1];
        if (adding) {
            chosen[0] = 0;
        } else if (rules.get(index).never) {
            chosen[0] = neverIndex;
        } else if (rules.get(index).hasReply()) {
            chosen[0] = sayIndex;
        } else {
            for (int i = 0; i < tones.length; i++) {
                if (tones[i] == rules.get(index).tone) chosen[0] = i;
            }
        }

        final Button action = new Button(activity);
        action.setText(actions[chosen[0]]);
        final Runnable syncReplyVisibility = () ->
                replyText.setVisibility(chosen[0] == sayIndex ? View.VISIBLE : View.GONE);
        action.setOnClickListener(v -> new AlertDialog.Builder(activity)
                .setTitle("Do what?")
                .setItems(actions, (d, which) -> {
                    chosen[0] = which;
                    action.setText(actions[which]);
                    syncReplyVisibility.run();
                }).show());

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        box.addView(pattern);
        box.addView(action);
        box.addView(replyText);
        syncReplyVisibility.run();

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(adding ? "New rule" : "Edit rule")
                .setMessage("Matched on whole words. \"eric\" catches Eric and Eric Jones, never "
                        + "Erica or Ericsson. A relationship you've set on a contact still beats "
                        + "this.")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String text = pattern.getText().toString().trim();
                    if (text.isEmpty()) {
                        toast("I need a word to match on.");
                        return;
                    }
                    boolean never = chosen[0] == neverIndex;
                    boolean say = chosen[0] == sayIndex;
                    String reply = say ? replyText.getText().toString().trim() : "";
                    if (say && reply.isEmpty()) {
                        toast("Give me the words to say, or pick a different action.");
                        return;
                    }
                    ResponderSettings.NameRule built = new ResponderSettings.NameRule(
                            text,
                            (never || say) ? null : tones[chosen[0]],
                            never, reply);
                    if (adding) rules.add(built);
                    else rules.set(index, built);
                    settings.setNameRules(rules);
                    // Transparency warning, non-blocking, same rule as the template editor: a
                    // fixed reply that names neither IRIS nor Suvra will read as though he typed
                    // it. His call, saved as written.
                    if (say && !ReplyTemplates.identifiesIris(reply)) {
                        new AlertDialog.Builder(activity)
                                .setTitle("Saved, but they'll think it was you")
                                .setMessage("That reply names neither me nor you, so whoever "
                                        + "matches this rule will assume you typed it yourself. "
                                        + "I've saved it exactly as written.")
                                .setPositiveButton("I know", null)
                                .show();
                    }
                    renderResponder(root);
                    toast(adding ? "Rule added." : "Rule updated.");
                    // Reopen the list so more rules can be added in one sitting \u2014 the owner
                    // asked for multiple entries, so adding should not kick him back out each time.
                    editNameRules(settings, root);
                });
        if (!adding) {
            builder.setNeutralButton("Delete", (d, w) -> {
                rules.remove(index);
                settings.setNameRules(rules);
                renderResponder(root);
                editNameRules(settings, root);
            });
        }
        builder.show();
    }
    /**
     * Choose a situational mode, or clear it.
     *
     * A mode overrides the time windows and the category content for as long as it's on, so the
     * list makes clear it's a manual switch the owner is expected to turn off again - unlike quiet
     * hours, nothing turns it off automatically.
     */
    private void editMode(final ResponderSettings settings, final View root) {
        final ResponderMode[] modes = ResponderMode.values();
        final String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            labels[i] = modes[i] == ResponderMode.NONE
                    ? "Off \u2014 normal behaviour"
                    : modes[i].label()
                        + (modes[i] == ResponderMode.SLEEPING
                            ? " (urgent senders told to call)" : "");
        }
        final String[] withEdit = new String[labels.length + 1];
        System.arraycopy(labels, 0, withEdit, 0, labels.length);
        withEdit[labels.length] = "Edit a mode's wording\u2026";
        choiceDialog("What are you doing?",
                "While a mode is on, I say the same thing to everyone regardless of the hour "
                        + "\u2014 it beats quiet hours and the categories. I won't turn it off for "
                        + "you, so remember you set it.",
                withEdit, which -> {
                    if (which == labels.length) {
                        editModeTemplatePick(settings, root);
                        return;
                    }
                    final ResponderMode chosen = modes[which];
                    settings.setMode(chosen);
                    renderResponder(root);
                    if (chosen == ResponderMode.NONE) {
                        toast("Mode off. Back to normal.");
                    } else if (chosen == ResponderMode.SLEEPING) {
                        toast("Sleeping \u2014 everyone gets the quiet-hours reply.");
                    } else {
                        // Offer to edit this mode's wording right here, so editing is never more
                        // than one tap from choosing it.
                        new AlertDialog.Builder(activity)
                                .setTitle(chosen.label() + " is on")
                                .setMessage("Everyone now gets the " + chosen.label().toLowerCase(
                                        java.util.Locale.ROOT) + " reply. Want to change the "
                                        + "wording?")
                                .setNegativeButton("It's fine", null)
                                .setPositiveButton("Edit wording", (dd, ww) ->
                                        editModeTemplate(settings, chosen, root))
                                .show();
                    }
                });
    }

    /** Pick which mode's wording to rewrite. */
    private void editModeTemplatePick(final ResponderSettings settings, final View root) {
        final java.util.List<ResponderMode> modes = new java.util.ArrayList<>();
        for (ResponderMode m : ResponderMode.values()) {
            if (m.isActive() && m != ResponderMode.SLEEPING) modes.add(m);
        }
        final String[] labels = new String[modes.size()];
        for (int i = 0; i < modes.size(); i++) labels[i] = modes.get(i).label();
        new AlertDialog.Builder(activity)
                .setTitle("Rewrite which mode?")
                .setMessage("Sleeping reuses your quiet-hours wording, so edit that under VOICE "
                        + "instead.")
                .setItems(labels, (d, which) ->
                        editModeTemplate(settings, modes.get(which), root))
                .show();
    }

    private void editModeTemplate(final ResponderSettings settings, final ResponderMode mode,
                                  final View root) {
        final ReplyTone.Tone tone = settings.defaultTone();
        java.util.List<String> shipped = ReplyTemplates.modeVariants(
                mode, MessageCategory.Kind.OTHER, tone);
        final EditText input = new EditText(activity);
        input.setHint(shipped.isEmpty() ? "your reply" : shipped.get(0));
        input.setText(settings.customModeTemplate(mode, tone));
        input.setMinLines(3);
        new AlertDialog.Builder(activity)
                .setTitle(mode.label() + " \u00b7 " + tone.name())
                .setMessage("Leave empty to use my wording. You can use {name}, {time} and "
                        + "{part_of_day}.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String text = input.getText().toString();
                    settings.setCustomModeTemplate(mode, tone, text);
                    if (!text.trim().isEmpty() && !ReplyTemplates.identifiesIris(text)) {
                        new AlertDialog.Builder(activity)
                                .setTitle("Saved, but they'll think it was you")
                                .setMessage("That wording names neither me nor you, so whoever "
                                        + "receives it will assume you typed it. Saved as "
                                        + "written.")
                                .setPositiveButton("I know", null)
                                .show();
                    }
                    renderResponder(root);
                }).show();
    }



    /** How many selected apps have a tone override set, for the row's value text. */
    private String appToneSummary(ResponderSettings settings) {
        java.util.Set<String> selected = settings.apps();
        if (selected == null || selected.isEmpty()) {
            return "no apps selected";
        }
        int n = 0;
        for (String pkg : selected) {
            if (settings.appTone(pkg) != null) n++;
        }
        return n == 0 ? "all default" : n + " set";
    }

    /** Pick an app, then set the tone IRIS uses for messages from it. Strictly lists responder-selected apps. */
    private void editAppTones(final ResponderSettings settings, final View root) {
        final java.util.List<String> packages = new java.util.ArrayList<>();
        if (settings.apps() != null) {
            for (String p : settings.apps()) {
                if (p != null && !p.trim().isEmpty()) packages.add(p.trim());
            }
        }
        if (packages.isEmpty()) {
            new AlertDialog.Builder(activity)
                    .setTitle("No responder apps selected")
                    .setMessage("IRIS can only apply a channel tone to apps you've allowed the "
                            + "responder to speak through.\n\nTap 'Who may I answer for you?' first "
                            + "to select your messaging apps.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        final String[] labels = new String[packages.size()];
        for (int i = 0; i < packages.size(); i++) {
            String pkg = packages.get(i);
            ReplyTone.Tone t = settings.appTone(pkg);
            String name = appLabel(pkg);
            labels[i] = name + "  \u2192  " + (t == null ? "default" : t.name());
        }
        choiceDialog("Tone by app",
                "Sound a certain way on a given channel \u2014 say professional on SMS, casual on "
                        + "WhatsApp. Strictly showing apps you have selected for the responder.",
                labels, which -> editOneAppTone(settings, packages.get(which), root));
    }

    private void editOneAppTone(final ResponderSettings settings, final String pkg,
                                final View root) {
        final ReplyTone.Tone[] tones = ReplyTone.Tone.values();
        final String[] labels = new String[tones.length + 1];
        for (int i = 0; i < tones.length; i++) labels[i] = tones[i].name();
        labels[tones.length] = "Use default (clear)";
        String name = appLabel(pkg);
        choiceDialog(name + " tone", "How should I sound on " + name + "?",
                labels, which -> {
                    settings.setAppTone(pkg, which < tones.length ? tones[which] : null);
                    renderResponder(root);
                });
    }

    private void editRelationTones(final ResponderSettings settings, final View root) {
        final ReplyTone.Relation[] relations = new ReplyTone.Relation[]{
                ReplyTone.Relation.FAMILY, ReplyTone.Relation.FRIEND, ReplyTone.Relation.WORK};
        final String[] labels = new String[relations.length];
        for (int i = 0; i < relations.length; i++) {
            String raw = settings.relationToneRaw(relations[i]);
            String displayTone = "DEFAULT".equalsIgnoreCase(raw)
                    ? "default (" + settings.defaultTone().name() + ")"
                    : raw;
            labels[i] = relations[i].name() + "  \u2192  " + displayTone;
        }
        choiceDialog("Tone by relationship",
                "Choose what tone IRIS uses for each relationship category. You can set a specific tone "
                        + "(Casual, Sarcastic, Professional, Brief) or set it to 'Use default tone' so it inherits "
                        + "your global default setting.",
                labels, which -> editOneRelationTone(settings, relations[which], root));
    }

    private void editOneRelationTone(final ResponderSettings settings, final ReplyTone.Relation relation,
                                     final View root) {
        final ReplyTone.Tone[] tones = ReplyTone.Tone.values();
        final String[] labels = new String[tones.length + 1];
        for (int i = 0; i < tones.length; i++) {
            labels[i] = tones[i] == ReplyTone.Tone.SARCASTIC
                    ? "SARCASTIC \u2014 only if you mean it" : tones[i].name();
        }
        labels[tones.length] = "Use default tone (" + settings.defaultTone().name() + ")";
        choiceDialog(relation.name() + " tone", "How should IRIS sound when replying to " + relation.name() + "?",
                labels, which -> {
                    settings.setRelationTone(relation, which < tones.length ? tones[which] : null);
                    renderResponder(root);
                });
    }

    /** Receives an edited list of mention handles/numbers. */
    private interface ListSink { void accept(java.util.List<String> v); }

    private String mentionSummary(java.util.List<String> items, String emptyText) {
        if (items == null || items.isEmpty()) return emptyText;
        if (items.size() == 1) return items.get(0);
        return items.get(0) + " +" + (items.size() - 1) + " more";
    }

    /** A multiline text editor for a list of handles/numbers (one per line or comma-separated). */
    private void editMentionList(final View root, String title, String hint,
                                 java.util.List<String> current, final ListSink sink) {
        final EditText input = new EditText(activity);
        input.setHint(hint);
        input.setMinLines(3);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setText(current == null ? "" : android.text.TextUtils.join("\n", current));
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        box.addView(input);
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    java.util.List<String> out = new java.util.ArrayList<>();
                    for (String part : input.getText().toString().split("[\\n,]+")) {
                        String t = part.trim();
                        if (!t.isEmpty()) out.add(t);
                    }
                    sink.accept(out);
                    renderResponder(root);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Human label for the stored context depth. */
    private String contextDepthLabel(int depth) {
        if (depth <= 0) return "just this message";
        if (depth == 1) return "just this message";
        return "last " + depth + " messages";
    }

    /** Pick how many recent messages of a chat IRIS keeps as context. */
    private void editContextDepth(final ResponderSettings settings, final View root) {
        final int[] options = {1, 2, 3, 5, 10};
        final String[] labels = new String[options.length];
        for (int i = 0; i < options.length; i++) {
            labels[i] = options[i] == 1 ? "Just this message"
                    : "Last " + options[i] + " messages";
        }
        choiceDialog("Context memory",
                "How many recent messages of a chat I read together, so a thought split across "
                        + "quick bubbles is understood as one. Stored on your phone.",
                labels, which -> {
                    settings.setContextDepth(options[which]);
                    renderResponder(root);
                });
    }

    private void editDefaultTone(final ResponderSettings settings, final View root) {
        final ReplyTone.Tone[] tones = ReplyTone.Tone.values();
        String[] labels = new String[tones.length];
        for (int i = 0; i < tones.length; i++) {
            labels[i] = tones[i] == ReplyTone.Tone.SARCASTIC
                    ? "SARCASTIC \u2014 only if you mean it" : tones[i].name();
        }
        new AlertDialog.Builder(activity)
                .setTitle("How should I sound by default?")
                .setItems(labels, (d, which) -> {
                    settings.setDefaultTone(tones[which]);
                    renderResponder(root);
                }).show();
    }

    /** Pick a category, then rewrite what IRIS says for it. */
    private void editTemplates(final ResponderSettings settings, final View root) {
        final java.util.List<MessageCategory.Kind> kinds = new java.util.ArrayList<>();
        final java.util.List<String> labels = new java.util.ArrayList<>();
        for (MessageCategory.Kind kind : MessageCategory.Kind.values()) {
            if (kind == MessageCategory.Kind.NONE) continue;
            boolean anywhere = false;
            // Listed if the category speaks in ANY tone, not just the current default. The old
            // check used the default tone only, so switching the default quietly changed which
            // categories could be edited at all.
            for (ReplyTone.Tone tone : ReplyTone.Tone.values()) {
                if (!ReplyTemplates.variants(kind, tone, false).isEmpty()
                        || !ReplyTemplates.variants(kind, tone, true).isEmpty()) {
                    anywhere = true;
                    break;
                }
            }
            if (!anywhere) continue;
            kinds.add(kind);
            String edited = editedCountFor(settings, kind);
            labels.add(categoryLabel(kind) + edited);
        }
        new AlertDialog.Builder(activity)
                .setTitle("Which reply should I rewrite?")
                .setItems(labels.toArray(new String[0]),
                        (d, which) -> editOneTemplate(settings, kinds.get(which),
                                settings.defaultTone(), false, root))
                .setNeutralButton("Reset all my words", (d, w) -> {
                    int removed = settings.resetAllTemplates();
                    toast(removed == 0 ? "Nothing to restore \u2014 they're all mine already."
                            : removed + " restored to my wording.");
                    renderResponder(root);
                }).show();
    }

    /** A marker on the category list so edited entries are visible before opening them. */
    private String editedCountFor(ResponderSettings settings, MessageCategory.Kind kind) {
        int count = 0;
        for (ReplyTone.Tone tone : ReplyTone.Tone.values()) {
            if (!settings.customTemplate(kind, tone, false).isEmpty()) count++;
            if (!settings.customTemplate(kind, tone, true).isEmpty()) count++;
        }
        return count == 0 ? "" : "   [" + count + " yours]";
    }

    /**
     * Rewrite one reply.
     *
     * Rebuilt after the owner called the first version confusing, and he was right. It was a bare
     * text box that silently edited only the current default tone, showed my wording as a
     * placeholder that vanished the moment he typed, and swapped between normal and quiet hours
     * through a button labelled with the hours he was NOT editing.
     *
     * Everything that was implicit is now on screen: which tone, which hours, my wording in full,
     * and a live preview with the placeholders filled in so he can see the actual sentence rather
     * than the template. Each of the eight slots per category is edited separately, because they
     * are genuinely different sentences.
     */
    private void editOneTemplate(final ResponderSettings settings,
                                 final MessageCategory.Kind kind, final ReplyTone.Tone tone,
                                 final boolean quiet, final View root) {
        final java.util.List<String> shipped = ReplyTemplates.variants(kind, tone, quiet);
        final String current = settings.customTemplate(kind, tone, quiet);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), 0);

        // Which situation this is, stated rather than implied, and tappable to move between them.
        box.addView(responderRow("Tone", tone.name(),
                () -> pickTemplateTone(settings, kind, quiet, root)));
        box.addView(responderRow("When", quiet ? "quiet hours" : "normal hours",
                () -> editOneTemplate(settings, kind, tone, !quiet, root)));

        if (shipped.isEmpty()) {
            box.addView(templateNote("I say nothing here by default. Anything you write becomes "
                    + "the whole reply for this situation."));
        } else {
            StringBuilder mine = new StringBuilder();
            for (int i = 0; i < shipped.size(); i++) {
                if (i > 0) mine.append('\n');
                mine.append("\u2022 ").append(shipped.get(i));
            }
            box.addView(templateLabel("MY WORDING"
                    + (shipped.size() > 1 ? " (" + shipped.size() + ", rotated)" : "")));
            box.addView(templateNote(mine.toString()));
        }

        box.addView(templateLabel("YOURS"));
        final EditText input = new EditText(activity);
        input.setHint("leave empty to keep my wording");
        input.setText(current);
        input.setMinLines(3);
        input.setTextSize(12f);
        box.addView(input);

        box.addView(templateLabel("PREVIEW"));
        final TextView preview = templateNote("");
        box.addView(preview);
        box.addView(templateNote("Placeholders: {name} {time} {part_of_day} "
                + "{hours_until_morning}"));

        // A live preview, because a template is not the sentence. Seeing "{hours_until_morning}"
        // resolve to "7" is the difference between guessing and knowing.
        final Runnable repaint = () -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) text = shipped.isEmpty() ? "" : shipped.get(0);
            preview.setText(text.isEmpty() ? "(silence)"
                    : ReplyTemplates.fill(text, "Ramesh", "21:40", "evening",
                            quiet ? 7 : -1, "Tuesday"));
        };
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) { repaint.run(); }
        });
        repaint.run();

        ScrollView scroller = new ScrollView(activity);
        scroller.addView(box);

        new AlertDialog.Builder(activity)
                .setTitle(categoryLabel(kind))
                .setView(scroller)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Use my wording", (d, w) -> {
                    settings.setCustomTemplate(kind, tone, quiet, "");
                    toast("Back to my words for that one.");
                    renderResponder(root);
                })
                .setPositiveButton("Save", (d, w) -> {
                    String text = input.getText().toString();
                    settings.setCustomTemplate(kind, tone, quiet, text);
                    // Non-blocking warning, not a refusal. Transparency is guaranteed for my
                    // own wording and the owner may knowingly opt out of it for his.
                    if (!text.trim().isEmpty() && !ReplyTemplates.identifiesIris(text)) {
                        new AlertDialog.Builder(activity)
                                .setTitle("Saved, but they'll think it was you")
                                .setMessage("That wording doesn't mention me or you by name, so "
                                        + "whoever receives it will assume you typed it. That's "
                                        + "your call \u2014 I've saved it exactly as written.")
                                .setPositiveButton("I know", null)
                                .show();
                    }
                    renderResponder(root);
                }).show();
    }

    /** Switch which tone's wording is being edited. */
    private void pickTemplateTone(final ResponderSettings settings,
                                  final MessageCategory.Kind kind, final boolean quiet,
                                  final View root) {
        final ReplyTone.Tone[] tones = ReplyTone.Tone.values();
        String[] labels = new String[tones.length];
        for (int i = 0; i < tones.length; i++) {
            boolean speaks = ReplyTone.answers(kind, tones[i]);
            labels[i] = tones[i].name() + (speaks ? "" : "   (silent here)");
        }
        new AlertDialog.Builder(activity)
                .setTitle("Which tone's wording?")
                .setItems(labels,
                        (d, which) -> editOneTemplate(settings, kind, tones[which], quiet, root))
                .show();
    }

    private TextView templateLabel(String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextSize(10f);
        label.setTypeface(android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD);
        label.setLetterSpacing(0.15f);
        label.setTextColor(getColor(R.color.magenta));
        label.setPadding(0, dp(12), 0, dp(3));
        return label;
    }

    private TextView templateNote(String text) {
        TextView note = new TextView(activity);
        note.setText(text);
        note.setTextSize(11f);
        note.setTypeface(android.graphics.Typeface.MONOSPACE);
        note.setTextColor(getColor(R.color.deck_text_dim));
        return note;
    }

    /** Wire the RESPONDER section. */
    public void bindResponderSection(final View view) {
        final ResponderSettings settings = new ResponderSettings(activity);

        View off = view.findViewById(R.id.responderStateOff);
        View dry = view.findViewById(R.id.responderStateDry);
        View live = view.findViewById(R.id.responderStateLive);

        if (off != null) {
            off.setOnClickListener(v -> {
                settings.setState(ResponderGuards.State.OFF, System.currentTimeMillis());
                toast("Stood down. I'll keep my opinions to myself.");
                renderResponder(view);
            });
        }
        if (dry != null) {
            // Arming even the dry run is an authenticated action. It is the step at which IRIS
            // begins forming opinions about Suvra's correspondence, which is worth a deliberate
            // gesture even though nothing can leave the phone yet.
            dry.setOnClickListener(v -> authenticateThen("Arm the responder in dry run", () -> {
                settings.setState(ResponderGuards.State.DRY_RUN, System.currentTimeMillis());
                toast("Dry run armed. I'll decide, and say nothing.");
                renderResponder(view);
            }));
        }
        if (live != null) {
            live.setOnClickListener(v -> confirmGoLive(settings, view));
        }

        View apps = view.findViewById(R.id.responderAppsButton);
        if (apps != null) {
            apps.setOnClickListener(v ->
                    ResponderAppPicker.show(activity, () -> renderResponder(view)));
        }

        Button urgentToggle = view.findViewById(R.id.responderUrgentOnlyToggle);
        if (urgentToggle != null) {
            urgentToggle.setOnClickListener(v -> {
                boolean next = !settings.urgentOnly();
                settings.setUrgentOnly(next);
                if (next) {
                    toast("Urgent-Only mode ACTIVE: IRIS will only reply to urgent or distressed messages.");
                } else {
                    toast("Urgent-Only mode OFF: Standard reply filters restored.");
                }
                renderResponder(view);
            });
        }

        View testSend = view.findViewById(R.id.responderTestSend);
        if (testSend != null) testSend.setOnClickListener(v -> testSendNow(view));

        View refreshLog = view.findViewById(R.id.responderRefreshLog);
        if (refreshLog != null) {
            refreshLog.setOnClickListener(v -> {
                renderResponder(view);
                toast("Caught up.");
            });
        }

        View clearLog = view.findViewById(R.id.responderClearLog);
        if (clearLog != null) {
            clearLog.setOnClickListener(v -> {
                new ResponderLog(activity).clear();
                toast("Log cleared. I remember nothing, as usual.");
                renderResponder(view);
            });
        }

        View reset = view.findViewById(R.id.responderResetButton);
        if (reset != null) {
            reset.setOnClickListener(v -> new AlertDialog.Builder(activity)
                    .setTitle("Reset everything?")
                    .setMessage("Every rule, every window, every word you've rewritten goes back "
                            + "to how I shipped. Your contact list goes too. This is the way back "
                            + "if you've edited me into something useless.")
                    .setNegativeButton("Keep my settings", null)
                    .setPositiveButton("Reset", (d, w) -> {
                        settings.resetEverything();
                        toast("Back to factory opinions.");
                        renderResponder(view);
                    }).show());
        }

        renderResponder(view);
    }

    /**
     * Go live, behind authentication and a confirmation that states the consequences concretely.
     *
     * The confirmation lists the actual settings rather than a generic warning, because "are you
     * sure?" is a question nobody reads. Naming the app count, the scope and the stand-down time
     * means the owner is agreeing to something specific.
     *
     * The dry run is strongly recommended and no longer mandatory. It was a hard block until the
     * owner asked for it to be removed; refusing to obey him is not my call to make. Skipping it
     * now costs an extra, blunter confirmation rather than a closed door. Authentication is
     * unchanged either way.
     */
    public void confirmGoLive(final ResponderSettings settings, final View view) {
        final ResponderLog log = new ResponderLog(activity);
        if (settings.apps().isEmpty()) {
            toast("Tell me which apps first, or I'll be live with nothing to speak through.");
            return;
        }
        if (log.all().isEmpty()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Live with no rehearsal, then")
                    .setMessage("You've not let me decide anything yet, so neither of us knows "
                            + "how I'll behave. The first thing you learn about my judgement "
                            + "will be whatever I've already said to somebody.\n\nI'd rather you "
                            + "armed DRY RUN for an hour. But it's your name on the messages, "
                            + "so it's your call.")
                    .setNegativeButton("Dry run first", (d, w) ->
                            authenticateThen("Arm the responder in dry run", () -> {
                                settings.setState(ResponderGuards.State.DRY_RUN,
                                        System.currentTimeMillis());
                                toast("Dry run armed. I'll decide, and say nothing.");
                                renderResponder(view);
                            }))
                    .setPositiveButton("Go live anyway", (d, w) -> goLiveNow(settings, view))
                    .show();
            return;
        }
        goLiveNow(settings, view);
    }

    /** The consequences, stated concretely, then authentication. */
    public void goLiveNow(final ResponderSettings settings, final View view) {
        ResponderLog log = new ResponderLog(activity);
        StringBuilder what = new StringBuilder();
        what.append("From the moment you confirm, I will send messages from your account "
                + "without asking you first.\n\n");
        what.append("\u2022 Through ").append(settings.apps().size())
                .append(settings.apps().size() == 1 ? " app\n" : " apps\n");
        what.append("\u2022 ").append(settings.replyToPersonal()
                ? "One-to-one chats: yes\n" : "One-to-one chats: no\n");
        what.append("\u2022 ").append(settings.replyToGroups()
                ? (settings.groupsRequireMention()
                    ? "Groups: only when you're named\n" : "Groups: all messages\n")
                : "Groups: no\n");
        what.append("\u2022 ").append(settings.replyToUnsaved()
                ? "Unknown numbers: yes\n" : "Unknown numbers: no\n");
        what.append("\u2022 Default tone: ").append(settings.defaultTone().name()).append('\n');
        what.append("\u2022 At most ").append(settings.dailyCapTotal())
                .append(" replies a day, ").append(settings.contactCapPerDay())
                .append(" per person\n");
        what.append("\u2022 ").append(settings.armedIndefinitely()
                ? "I will stay armed until you stop me"
                : "I'll stand down after " + (settings.disarmAfterMs() / 3600000) + " hours");
        what.append("\n\nEvery message will say it came from me and not from you. ");
        if (log.all().isEmpty()) {
            what.append("You have no dry-run history, so this is the first thing I'll have "
                    + "done unsupervised.");
        } else {
            what.append(log.wouldHaveSentCount())
                    .append(" of my last ").append(log.all().size())
                    .append(" decisions would have been sent \u2014 read them first if you "
                            + "haven't.");
        }

        new AlertDialog.Builder(activity)
                .setTitle("Let me speak for you?")
                .setMessage(what.toString())
                .setNegativeButton("Stay in dry run", null)
                .setPositiveButton("Go live", (d, w) ->
                        authenticateThen("Let IRIS send messages on your behalf", () -> {
                            settings.setState(ResponderGuards.State.LIVE,
                                    System.currentTimeMillis());
                            toast("Live. I'll be careful with your name.");
                            renderResponder(view);
                        }))
                .show();
    }

    /** Redraw the responder status, settings rows and decision log. */
    public void renderResponder(View view) {
        final ResponderSettings settings = new ResponderSettings(activity);
        long now = System.currentTimeMillis();

        TextView status = view.findViewById(R.id.responderStatus);
        if (status != null) {
            status.setText(responderStatusText(settings, now));
            boolean off = settings.state() == ResponderGuards.State.OFF;
            status.setTextColor(getColor(off ? R.color.deck_text_dim : R.color.deck_accent));
        }
        highlightResponderState(view, settings.state());

        Button urgentToggle = view.findViewById(R.id.responderUrgentOnlyToggle);
        if (urgentToggle != null) {
            boolean active = settings.urgentOnly();
            urgentToggle.setText(active ? "⚠️ URGENT MESSAGES ONLY: ACTIVE" : "URGENT MESSAGES ONLY: OFF");
            urgentToggle.setTextColor(getColor(active ? R.color.deck_error : R.color.deck_accent));
            urgentToggle.setBackgroundResource(active ? R.drawable.bg_cyber_button_danger : R.drawable.bg_cyber_button);
            urgentToggle.setTypeface(android.graphics.Typeface.MONOSPACE,
                    active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }

        LinearLayout host = view.findViewById(R.id.responderSettingsHost);
        if (host != null) {
            host.removeAllViews();
            host.addView(sectionLabel("MODE"));
            host.addView(sectionNote("How IRIS answers right now. Auto-sleep answers as "
                    + "\u201Casleep\u201D during your quiet hours."));
            host.addView(responderRow("Situation",
                    settings.mode().isActive() ? settings.mode().label() : "none",
                    () -> editMode(settings, view)));
            host.addView(responderSwitch("Auto-sleep during quiet hours",
                    settings.autoSleepInQuietHours(),
                    on -> settings.setAutoSleepInQuietHours(on), view));
            host.addView(responderSwitch("Auto-driving when connected to car Bluetooth",
                    settings.autoDriving(),
                    on -> settings.setAutoDriving(on), view));
            host.addView(responderSwitch("Auto-meeting during busy calendar events",
                    settings.autoMeeting(),
                    on -> settings.setAutoMeeting(on), view));
            host.addView(responderRow("Context memory",
                    contextDepthLabel(settings.contextDepth()),
                    () -> editContextDepth(settings, view)));
            host.addView(responderSwitch("Time-of-day flavour in replies",
                    settings.timeFlavorEnabled(),
                    on -> settings.setTimeFlavorEnabled(on), view));
            host.addView(sectionLabel("SCOPE"));
            host.addView(sectionNote("Who IRIS may reply to. In groups it stays quiet unless "
                    + "you're named by name, @handle or number."));
            host.addView(responderSwitch("Urgent messages only",
                    settings.urgentOnly(),
                    on -> {
                        settings.setUrgentOnly(on);
                        toast(on ? "Urgent-Only mode ACTIVE: only urgent/distressed messages answered"
                                 : "Urgent-Only mode OFF: all allowed categories answered");
                    }, view));
            host.addView(responderSwitch("Answer one-to-one chats",
                    settings.replyToPersonal(), on -> settings.setReplyToPersonal(on), view));
            host.addView(responderSwitch("Answer group chats",
                    settings.replyToGroups(), on -> settings.setReplyToGroups(on), view));
            host.addView(responderSwitch("In groups, only when I'm named",
                    settings.groupsRequireMention(),
                    on -> settings.setGroupsRequireMention(on), view));
            host.addView(responderRow("My @handle(s) for group mentions",
                    mentionSummary(settings.mentionHandles(), "no handle set"),
                    () -> editMentionList(view, "My @handle(s)",
                            "One per line (e.g. you_know__who__i__am). Underscores matter.",
                            settings.mentionHandles(), settings::setMentionHandles)));
            host.addView(responderRow("My number(s) for group mentions",
                    mentionSummary(settings.mentionNumbers(), "no number set"),
                    () -> editMentionList(view, "My number(s)",
                            "One per line, with or without +country code.",
                            settings.mentionNumbers(), settings::setMentionNumbers)));
            host.addView(responderRow("Group trigger words (comma-separated)",
                    mentionSummary(settings.mentionKeywords(), "no trigger words set"),
                    () -> editMentionList(view, "Group trigger words",
                            "Comma-separated or one per line (e.g. suvra, stg, emergency, bot).",
                            settings.mentionKeywords(), settings::setMentionKeywords)));
            host.addView(responderSwitch("Answer numbers not in your contacts",
                    settings.replyToUnsaved(), on -> settings.setReplyToUnsaved(on), view));
            host.addView(responderSwitch("Only contacts you've explicitly allowed",
                    settings.allowlistOnly(), on -> settings.setAllowlistOnly(on), view));
            host.addView(responderSwitch("Answer even while I'm using the phone",
                    settings.replyWhileInUse(), on -> settings.setReplyWhileInUse(on), view));
            host.addView(responderSwitch("Let family ring my phone when urgent",
                    settings.familyRingEnabled(),
                    on -> settings.setFamilyRingEnabled(on), view));
            host.addView(responderSwitch("Reply to every message, not one per burst",
                    settings.replyToEveryMessage(),
                    on -> settings.setReplyToEveryMessage(on), view));
            host.addView(responderSwitch("Keep messages unread after I reply",
                    settings.keepUnread(),
                    on -> settings.setKeepUnread(on), view));

            host.addView(sectionLabel("TIMING"));
            host.addView(responderRow("Quiet hours",
                    ResponderWindow.formatMinute(settings.quietStart()) + " \u2192 "
                            + ResponderWindow.formatMinute(settings.quietEnd()),
                    () -> editQuietHours(settings, view)));
            host.addView(responderRow("Cooldown per chat",
                    (settings.cooldownMs() / 60000) + " min",
                    () -> editNumber("Cooldown in minutes",
                            settings.cooldownMs() / 60000,
                            value -> settings.setCooldownMs(value * 60000L), view)));
            host.addView(responderRow("Stand down after",
                    settings.armedIndefinitely() ? "never \u2014 until you stop me"
                            : (settings.disarmAfterMs() / 3600000) + " h",
                    () -> editDisarm(settings, view)));

            host.addView(sectionLabel("LIMITS"));
            host.addView(responderRow("Replies per contact per day",
                    String.valueOf(settings.contactCapPerDay()),
                    () -> editNumber("Replies per contact per day",
                            settings.contactCapPerDay(),
                            value -> settings.setContactCapPerDay((int) value), view)));
            host.addView(responderRow("Replies per day in total",
                    String.valueOf(settings.dailyCapTotal()),
                    () -> editNumber("Replies per day in total", settings.dailyCapTotal(),
                            value -> settings.setDailyCapTotal((int) value), view)));

            host.addView(sectionLabel("VOICE"));
            // The effective tone is shown, not just the stored default. The owner set SARCASTIC
            // and kept receiving PROFESSIONAL because the work-hours window outranks the global
            // default and had no UI at all -- a rule he could neither see nor switch off.
            host.addView(responderRow("Default tone",
                    settings.defaultTone().name() + effectiveToneNote(settings),
                    () -> editDefaultTone(settings, view)));
            host.addView(responderRow("Tone by relationship", relationToneSummary(settings),
                    () -> editRelationTones(settings, view)));
            host.addView(responderRow("Tone by app", appToneSummary(settings),
                    () -> editAppTones(settings, view)));
            host.addView(responderSwitch("Sound professional during work hours",
                    settings.workHoursForceProfessional(),
                    on -> settings.setWorkHoursForceProfessional(on), view));
            host.addView(responderRow("Work hours",
                    ResponderWindow.formatMinute(settings.workStart()) + " \u2192 "
                            + ResponderWindow.formatMinute(settings.workEnd()),
                    () -> editWorkHours(settings, view)));
            host.addView(responderRow("Rules by name", nameRuleSummary(settings),
                    () -> editNameRules(settings, view)));
            host.addView(responderRow("Rewrite what I say", "per category",
                    () -> editTemplates(settings, view)));

            host.addView(sectionLabel("WHAT I ANSWER"));
            for (MessageCategory.Kind kind : MessageCategory.Kind.values()) {
                if (kind == MessageCategory.Kind.NONE) continue;
                // REMINDER isn't a yes/no reply category; it has its own section below.
                if (kind == MessageCategory.Kind.REMINDER) continue;
                final MessageCategory.Kind current = kind;
                host.addView(responderSwitch(categoryLabel(kind),
                        settings.categoryEnabled(kind, settings.defaultTone()),
                        on -> settings.setCategoryEnabled(current, on), view));
            }

            host.addView(sectionLabel("REMINDERS"));
            host.addView(responderRow("Who may set reminders",
                    reminderScopeLabel(settings.reminderScope()),
                    () -> editReminderScope(settings, view)));
            renderPendingReminders(host, view);

            host.addView(sectionLabel("ACCESS"));
            addAccessRows(host, view);
        }

        renderResponderLog(view);
        startResponderLogWatch(view);
    }

    /**
     * One-tap rows for the two permissions the reminders and ring need, which Android only grants
     * from its own settings screens. Each shows whether it's already granted and deep-links to the
     * right screen on tap, so the owner never has to hunt through system settings.
     */
    private void addAccessRows(LinearLayout host, final View root) {
        // Exact alarms \u2014 so a reminder fires at the minute, not whenever the OS batches it.
        boolean exactOk = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.app.AlarmManager am =
                    (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            exactOk = am != null && am.canScheduleExactAlarms();
        }
        host.addView(responderRow("Exact alarms (for reminders)",
                exactOk ? "granted" : "tap to allow", () -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            startActivity(new android.content.Intent(
                                    android.provider.Settings
                                            .ACTION_REQUEST_SCHEDULE_EXACT_ALARM));
                        } catch (Throwable t) {
                            toast("Open Settings \u2192 Apps \u2192 IRIS \u2192 Alarms & reminders.");
                        }
                    } else {
                        toast("Already allowed on this Android version.");
                    }
                }));

        // Notification-policy access \u2014 so the ring can override Do Not Disturb.
        boolean dndOk = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            dndOk = nm != null && nm.isNotificationPolicyAccessGranted();
        }
        host.addView(responderRow("Override Do Not Disturb (for the ring)",
                dndOk ? "granted" : "tap to allow", () -> {
                    try {
                        startActivity(new android.content.Intent(android.provider.Settings
                                .ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                    } catch (Throwable t) {
                        toast("Open Settings \u2192 Apps \u2192 Special access \u2192 "
                                + "Do Not Disturb access.");
                    }
                }));
    }

    private String reminderScopeLabel(ResponderSettings.ReminderScope scope) {
        switch (scope) {
            case ANYONE: return "anyone who messages";
            case FAMILY: return "family only";
            default: return "your contacts";
        }
    }

    private void editReminderScope(final ResponderSettings settings, final View root) {
        final ResponderSettings.ReminderScope[] scopes = ResponderSettings.ReminderScope.values();
        String[] labels = new String[scopes.length];
        for (int i = 0; i < scopes.length; i++) labels[i] = reminderScopeLabel(scopes[i]);
        choiceDialog("Who may set a reminder?",
                "Someone texting \u201Cremind Suvra to...\u201D sets an alarm on your phone. "
                        + "Contacts-only is the safe default; a stranger shouldn't be able to.",
                labels, which -> {
                    settings.setReminderScope(scopes[which]);
                    renderResponder(root);
                });
    }

    /** The pending reminders, each with a delete, so the owner sees even undated ones. */
    private void renderPendingReminders(LinearLayout host, final View root) {
        java.util.List<ReminderStore.Reminder> pending = new ReminderStore(activity).pending();
        if (pending.isEmpty()) {
            TextView none = new TextView(activity);
            none.setText("No reminders set. Someone can text \u201Cremind Suvra to...\u201D.");
            none.setTextSize(11f);
            none.setTypeface(android.graphics.Typeface.MONOSPACE);
            none.setTextColor(getColor(R.color.deck_text_dim));
            none.setPadding(0, dp(6), 0, 0);
            host.addView(none);
            return;
        }
        for (final ReminderStore.Reminder r : pending) {
            String when = r.undated() ? "no time set"
                    : DeletedMessageRules.formatTime(r.dueMs, java.util.TimeZone.getDefault());
            String who = r.sender == null || r.sender.isEmpty() ? "" : "  \u00b7  from " + r.sender;
            View row = responderRow(r.task.isEmpty() ? "(reminder)" : r.task, when + who, () -> {});
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(activity)
                        .setTitle("Delete this reminder?")
                        .setMessage("\u201C" + r.task + "\u201D")
                        .setNegativeButton("Keep", null)
                        .setPositiveButton("Delete", (d, w) -> {
                            ReminderStore store = new ReminderStore(activity);
                            store.remove(r.id);
                            ReminderStore.cancel(activity, r.id);
                            renderResponder(root);
                        }).show();
                return true;
            });
            host.addView(row);
        }
    }

    /** Signature of the decision log as last drawn, so polling can detect a real change. */
    private String responderLogSignature = "";
    /** Active filter for decision log: 0=ALL, 1=SPOKE, 2=SILENT, 3=DRY RUN, 4=FAILED */
    private int responderLogFilter = 0;

    /**
     * Attempt one real reply now and report exactly what came back.
     *
     * Added because "the log said sent and nothing arrived" is not diagnosable by reading code.
     * Waiting for a message to arrive confounds three separate questions -- did the message get
     * classified, did the guards allow it, did the send succeed -- and answering them one build at
     * a time is slow. This isolates the third.
     *
     * It sends a REAL message, so it names the recipient first and requires confirmation. It does
     * not touch cooldowns, caps or the decision log: it is a measurement of the send path, not a
     * decision, and letting it consume allowances would distort the thing being measured.
     */
    public void testSendNow(final View root) {
        final ResponderSettings settings = new ResponderSettings(activity);
        final java.util.List<android.service.notification.StatusBarNotification> candidates =
                new java.util.ArrayList<>();
        final java.util.List<String> labels = new java.util.ArrayList<>();

        for (android.service.notification.StatusBarNotification sbn
                : IrisNotificationListener.activeForReply()) {
            if (sbn == null || sbn.getPackageName() == null) continue;
            if (!settings.allowsApp(sbn.getPackageName())) continue;
            if (IrisNotificationListener.replyAction(sbn) == null) continue;
            CharSequence title = sbn.getNotification().extras
                    .getCharSequence(android.app.Notification.EXTRA_TITLE);
            candidates.add(sbn);
            labels.add((title == null ? "(no title)" : title.toString())
                    + "\n" + appLabel(sbn.getPackageName()));
        }

        if (candidates.isEmpty()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Nothing I can reply to right now")
                    .setMessage("I need a notification showing, from an app you've ticked for me, "
                            + "that offers a reply box.\n\nSend yourself a message and leave the "
                            + "notification in the shade \u2014 don't open the chat, because that "
                            + "dismisses the notification and the reply handle lives on it.")
                    .setPositiveButton("Right", null)
                    .show();
            return;
        }

        new AlertDialog.Builder(activity)
                .setTitle("Reply to which one?")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    final android.service.notification.StatusBarNotification chosen =
                            candidates.get(which);
                    new AlertDialog.Builder(activity)
                            .setTitle("This sends a real message")
                            .setMessage("I'll send \u201CTest from IRIS, Suvra's assistant. "
                                    + "Ignore this.\u201D to " + labels.get(which).replace('\n', ' ')
                                    + ", right now, and tell you precisely what the app said "
                                    + "back.\n\nThis doesn't use up any of your daily allowance "
                                    + "and won't appear in the decision log \u2014 it's a test of "
                                    + "the send, not a decision.")
                            .setNegativeButton("Don't", null)
                            .setPositiveButton("Send it", (d2, w2) -> {
                                NotificationReply.Result result = NotificationReply.send(
                                        activity, chosen,
                                        "Test from IRIS, Suvra's assistant. Ignore this.",
                                        "auto_reply_test", false);
                                new AlertDialog.Builder(activity)
                                        .setTitle(NotificationReply.sent(result)
                                                ? "Handed over" : "Refused")
                                        .setMessage("Result: " + result.name() + "\n\n"
                                                + NotificationReply.explain(result)
                                                + (NotificationReply.sent(result)
                                                    ? "\n\nIf it doesn't appear in the chat, the "
                                                      + "app accepted the handle and then did "
                                                      + "nothing with it \u2014 which is the app's "
                                                      + "doing, not mine, and nothing on this "
                                                      + "phone can see past that point."
                                                    : ""))
                                        .setPositiveButton("Noted", null)
                                        .show();
                            }).show();
                }).show();
    }

    /**
     * Watch the decision log while the RESPONDER tab is open.
     *
     * Decisions are written by the notification listener on a background thread, so on device the
     * log appeared frozen: entries only showed up after leaving the tab and coming back, which
     * made the dry run look broken when it was working.
     *
     * Polls rather than registering a listener because the writer is a different process component
     * and a preference-change listener would fire for every counter update too. Only the log host
     * is redrawn, and only when the signature actually changed -- rebuilding the settings rows on a
     * timer would fight the owner mid-toggle and reset his scroll position.
     *
     * Self-cancelling: the posted runnable stops as soon as the view leaves the window or the owner
     * moves to another sub-tab, so there is no timer outliving the screen.
     */
    private void startResponderLogWatch(final View view) {
        responderLogSignature = new ResponderLog(activity).signature();
        view.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                if (!view.isAttachedToWindow() || (activity != null && activity.getCommsSection() != 1)) return;
                String current = new ResponderLog(activity).signature();
                if (!current.equals(responderLogSignature)) {
                    responderLogSignature = current;
                    renderResponderLog(view);
                }
                view.postDelayed(this, 2500);
            }
        }, 2500);
    }

    private String responderStatusText(ResponderSettings settings, long now) {
        ResponderGuards.State state = settings.state();
        if (state == ResponderGuards.State.OFF) {
            return "I'm off. Nothing I decide reaches anyone, because I'm not deciding.";
        }
        int appCount = settings.apps().size();
        if (appCount == 0) {
            return "Armed, but you haven't told me which apps I may speak through \u2014 so I am "
                    + "watching nothing. Tap the button above.";
        }
        StringBuilder out = new StringBuilder();
        out.append(state == ResponderGuards.State.DRY_RUN
                ? "DRY RUN. I'm deciding and writing it down, and sending nothing. "
                : "LIVE. I am answering for you. ");
        out.append("Watching ").append(appCount).append(appCount == 1 ? " app" : " apps");
        if (settings.armedIndefinitely()) {
            out.append(", with no stand-down time \u2014 you'll have to stop me yourself.");
        } else {
            long left = settings.armedUntilMs() - now;
            out.append(left > 0
                    ? ", standing down in about " + Math.max(1, left / 3600000) + " h."
                    : ". My armed period has run out, so I've stood down.");
        }
        if (settings.mode().isActive()) {
            out.append("\nMode: ").append(settings.mode().label())
                    .append(" \u2014 everyone gets the same reply until you turn it off.");
        }
        if (settings.urgentOnly()) {
            out.append("\n\u26A0\uFE0F URGENT-ONLY: Silencing casual messages. Answering only urgent or distressed messages.");
        }
        return out.toString();
    }

    private void highlightResponderState(View view, ResponderGuards.State state) {
        int[] ids = {R.id.responderStateOff, R.id.responderStateDry, R.id.responderStateLive};
        ResponderGuards.State[] states = {ResponderGuards.State.OFF,
                ResponderGuards.State.DRY_RUN, ResponderGuards.State.LIVE};
        for (int i = 0; i < ids.length; i++) {
            TextView button = view.findViewById(ids[i]);
            if (button == null) continue;
            boolean active = states[i] == state;
            boolean danger = i == 2;
            button.setTextColor(getColor(active ? R.color.magenta
                    : danger ? R.color.deck_error : R.color.deck_inactive));
            button.setTypeface(android.graphics.Typeface.MONOSPACE,
                    active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private String categoryLabel(MessageCategory.Kind kind) {
        switch (kind) {
            case GREETING: return "Plain hellos";
            case TIME_GREETING: return "Good morning / night, gm, gn";
            case FESTIVAL: return "Festival wishes";
            case BIRTHDAY: return "Birthday wishes";
            case WEEKEND: return "Weekend wishes";
            case THANKS: return "Thanks, ty, thx";
            case CONGRATS: return "Congratulations, well done, badhai";
            case TO_IRIS: return "When someone's talking to me, not you";
            case REMINDER: return "Reminders (\u201Cremind me to...\u201D)";
            case OTHER: return "Everything else (answers ordinary messages)";
            case CHECK_IN: return "Are you there?";
            case QUESTION: return "Questions";
            case URGENT: return "Urgent";
            case MENTION: return "When I'm named in a group";
            case SHORT: return "One or two word messages";
            case ACKNOWLEDGEMENT: return "\u201Cok\u201D (sarcastic tone only)";
            case MEDIA_ONLY: return "Photos and stickers";
            default: return kind.name();
        }
    }

    private TextView sectionLabel(String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextSize(10f);
        label.setTypeface(android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD);
        label.setLetterSpacing(0.2f);
        label.setTextColor(getColor(R.color.magenta));
        label.setPadding(0, dp(14), 0, dp(4));
        return label;
    }

    /** A small, dim explanatory line under a section label or row. */
    private TextView sectionNote(String text) {
        TextView note = new TextView(activity);
        note.setText(text);
        note.setTextSize(11f);
        note.setTextColor(getColor(R.color.deck_text_dim));
        note.setPadding(0, 0, 0, dp(6));
        return note;
    }

    /** Callback for a boolean responder setting. */
    private interface Toggle { void set(boolean on); }

    /** A pick-one-from-a-list callback for choiceDialog. */
    private interface OnPick { void pick(int which); }

    /**
     * A list-choice dialog that ALSO shows an explanation.
     *
     * AlertDialog cannot show setMessage() and setItems() together: the message takes the content
     * area and the item list silently never appears. That is exactly why the mode, rules-by-name
     * and reminder-scope choosers looked uneditable on the device \u2014 an explanatory paragraph
     * with a Close button and nothing to tap. This renders the explanation as a header above
     * real, tappable rows inside a scroll view, so both always show.
     */
    private void choiceDialog(String title, String message, String[] labels, final OnPick onPick) {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        scroll.addView(box);

        if (message != null && !message.isEmpty()) {
            TextView msg = new TextView(activity);
            msg.setText(message);
            msg.setTextSize(13f);
            msg.setTypeface(android.graphics.Typeface.MONOSPACE);
            msg.setTextColor(getColor(R.color.deck_text_dim));
            msg.setPadding(0, 0, 0, dp(8));
            box.addView(msg);
        }

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();

        if (labels != null) {
            for (int i = 0; i < labels.length; i++) {
                final int which = i;
                TextView row = new TextView(activity);
                row.setText(labels[i]);
                row.setTextSize(15f);
                row.setTypeface(android.graphics.Typeface.MONOSPACE);
                row.setTextColor(getColor(R.color.deck_text));
                row.setMinHeight(dp(48));
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(10), dp(10), dp(10));
                row.setClickable(true);
                row.setBackgroundResource(R.drawable.bg_cyber_row);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(6);
                row.setLayoutParams(lp);
                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    onPick.pick(which);
                });
                box.addView(row);
            }
        }
        dialog.show();
    }

    /** Callback for a numeric responder setting. */
    private interface Number { void set(long value); }

    private View responderSwitch(String label, boolean value, final Toggle toggle,
                                 final View root) {
        Switch box = new Switch(activity);
        box.setText(label);
        box.setChecked(value);
        box.setTextSize(12f);
        box.setTypeface(android.graphics.Typeface.MONOSPACE);
        box.setTextColor(getColor(R.color.deck_text));
        box.setMinHeight(dp(44));
        box.setOnCheckedChangeListener((b, on) -> {
            toggle.set(on);
            renderResponder(root);
        });
        return box;
    }

    private View responderRow(String label, String value, final Runnable onTap) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setMinimumHeight(dp(44));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_cyber_row);
        row.setPadding(dp(11), dp(9), dp(11), dp(9));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(5);
        row.setLayoutParams(params);

        TextView name = new TextView(activity);
        name.setText(label);
        name.setTextSize(12f);
        name.setTypeface(android.graphics.Typeface.MONOSPACE);
        name.setTextColor(getColor(R.color.deck_text));
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        TextView shown = new TextView(activity);
        shown.setText(value);
        shown.setTextSize(12f);
        shown.setTypeface(android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD);
        shown.setTextColor(getColor(R.color.deck_accent));
        row.addView(shown);

        row.setOnClickListener(v -> onTap.run());
        return row;
    }

    public void renderResponderLog(View view) {
        ResponderLog log = new ResponderLog(activity);
        java.util.List<ResponderLog.Entry> entries = log.newestFirst();

        TextView summary = view.findViewById(R.id.responderLogSummary);
        if (summary != null) {
            if (entries.isEmpty()) {
                summary.setText("Nothing decided yet. Arm the dry run and let a message arrive.");
            } else {
                StringBuilder line = new StringBuilder();
                line.append(entries.size()).append(" decisions \u00b7 ")
                        .append(log.wouldHaveSentCount()).append(" with something to say");
                int sent = log.sentCount();
                int failed = log.failedCount();
                if (sent > 0) line.append(" \u00b7 ").append(sent).append(" sent");
                if (failed > 0) {
                    line.append(" \u00b7 ").append(failed)
                            .append(failed == 1 ? " FAILED to send" : " FAILED to send");
                }
                summary.setText(line.toString());
                summary.setTextColor(getColor(
                        failed > 0 ? R.color.deck_error : R.color.deck_text_dim));
            }
        }

        LinearLayout filters = view.findViewById(R.id.responderLogFilters);
        if (filters != null) {
            filters.removeAllViews();
            if (!entries.isEmpty()) {
                int total = entries.size();
                int spokeCount = 0;
                int silentCount = 0;
                int dryCount = 0;
                int failedCount = 0;
                for (ResponderLog.Entry e : entries) {
                    if (e.wouldHaveSent()) spokeCount++;
                    else silentCount++;
                    if (e.dryRun) dryCount++;
                    if (e.sendFailed()) failedCount++;
                }

                addResponderLogChip(filters, "ALL (" + total + ")", 0, view);
                addResponderLogChip(filters, "SPOKE 💬 (" + spokeCount + ")", 1, view);
                addResponderLogChip(filters, "SILENT 🤫 (" + silentCount + ")", 2, view);
                if (dryCount > 0) {
                    addResponderLogChip(filters, "DRY RUN 🧪 (" + dryCount + ")", 3, view);
                }
                if (failedCount > 0) {
                    addResponderLogChip(filters, "FAILED ❌ (" + failedCount + ")", 4, view);
                }
            }
        }

        LinearLayout host = view.findViewById(R.id.responderLogHost);
        if (host == null) return;
        host.removeAllViews();

        if (entries.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("The silences will show up here too, with my reasons. That is the "
                    + "point of the dry run \u2014 you get to audit my judgement before I'm "
                    + "allowed to use it.");
            empty.setTextSize(11f);
            empty.setTypeface(android.graphics.Typeface.MONOSPACE);
            empty.setTextColor(getColor(R.color.deck_text_dim));
            empty.setPadding(0, dp(10), 0, dp(10));
            host.addView(empty);
            return;
        }

        java.util.List<ResponderLog.Entry> filtered = new java.util.ArrayList<>();
        for (ResponderLog.Entry e : entries) {
            if (responderLogFilter == 1 && !e.wouldHaveSent()) continue;
            if (responderLogFilter == 2 && e.wouldHaveSent()) continue;
            if (responderLogFilter == 3 && !e.dryRun) continue;
            if (responderLogFilter == 4 && !e.sendFailed()) continue;
            filtered.add(e);
        }

        if (filtered.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("No decisions match this filter. Tap 'ALL' above to view all.");
            empty.setTextSize(11f);
            empty.setTypeface(android.graphics.Typeface.MONOSPACE);
            empty.setTextColor(getColor(R.color.deck_text_dim));
            empty.setPadding(0, dp(14), 0, dp(14));
            host.addView(empty);
            return;
        }

        for (ResponderLog.Entry entry : filtered) host.addView(responderLogCard(entry, view));
    }

    private void addResponderLogChip(LinearLayout parent, String label, final int filterIndex, final View rootView) {
        TextView chip = new TextView(activity);
        chip.setText(label);
        chip.setTextSize(10f);
        chip.setTypeface(android.graphics.Typeface.MONOSPACE,
                responderLogFilter == filterIndex ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        chip.setLayoutParams(lp);

        boolean active = responderLogFilter == filterIndex;
        chip.setTextColor(getColor(active ? R.color.text_primary : R.color.deck_text_dim));
        chip.setBackgroundResource(active ? R.drawable.bg_cyber_button : R.drawable.bg_cyber_row);

        chip.setOnClickListener(v -> {
            responderLogFilter = filterIndex;
            renderResponderLog(rootView);
        });
        parent.addView(chip);
    }

    /**
     * One decision.
     *
     * Enhanced cyber layout with prominent status badge, incoming/outgoing bubbles,
     * category/tone tags, and tap-for-audit.
     */
    private View responderLogCard(ResponderLog.Entry entry, final View root) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        card.setLayoutParams(params);

        boolean spoke = entry.wouldHaveSent();
        boolean failed = entry.sendFailed();
        boolean sent = entry.actuallySent();

        card.setBackgroundResource(R.drawable.bg_cyber_panel);

        // 1. TOP BAR: [App Badge] Sender Name ......... [Status Pill]
        LinearLayout topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(android.view.Gravity.CENTER_VERTICAL);

        MessagingApp channel = MessagingApp.fromPackage(entry.pkg);
        String channelName = channel != MessagingApp.OTHER ? channel.label() : (entry.appLabel.isEmpty() ? "App" : entry.appLabel);
        TextView channelBadge = new TextView(activity);
        channelBadge.setText("[" + channelName + "]");
        channelBadge.setTextSize(10f);
        channelBadge.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        channelBadge.setTextColor(getColor(R.color.deck_accent));
        topBar.addView(channelBadge);

        String who = entry.sender.isEmpty() ? "Someone" : entry.sender;
        TextView senderTv = new TextView(activity);
        senderTv.setText(" " + who + (entry.group ? " (group)" : ""));
        senderTv.setTextSize(12f);
        senderTv.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        senderTv.setTextColor(getColor(R.color.text_primary));
        senderTv.setSingleLine(true);
        senderTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams senderLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        senderTv.setLayoutParams(senderLp);
        topBar.addView(senderTv);

        TextView statusBadge = new TextView(activity);
        statusBadge.setTextSize(10f);
        statusBadge.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        statusBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
        if (failed) {
            statusBadge.setText("❌ FAILED");
            statusBadge.setTextColor(getColor(R.color.deck_error));
            statusBadge.setBackgroundResource(R.drawable.bg_cyber_button_danger);
        } else if (sent) {
            statusBadge.setText("💬 SENT");
            statusBadge.setTextColor(getColor(R.color.mint));
            statusBadge.setBackgroundResource(R.drawable.bg_cyber_button);
        } else if (spoke) {
            statusBadge.setText("🧪 WOULD SEND");
            statusBadge.setTextColor(getColor(R.color.violet));
            statusBadge.setBackgroundResource(R.drawable.bg_cyber_button);
        } else {
            statusBadge.setText("🤫 SILENT");
            statusBadge.setTextColor(getColor(R.color.deck_text_dim));
        }
        topBar.addView(statusBadge);
        card.addView(topBar);

        // 2. META ROW: Time · Category · Tone · Dry Run
        TextView meta = new TextView(activity);
        String timeStr = DeletedMessageRules.formatTime(entry.whenMs, java.util.TimeZone.getDefault());
        StringBuilder metaSb = new StringBuilder();
        metaSb.append("🕒 ").append(timeStr);
        if (entry.category != null && !entry.category.isEmpty()) {
            metaSb.append("  ·  🏷️ ").append(entry.category);
        }
        if (entry.tone != null && !entry.tone.isEmpty()) {
            metaSb.append("  ·  🎭 ").append(entry.tone);
        }
        if (entry.dryRun) {
            metaSb.append("  ·  DRY RUN");
        }
        meta.setText(metaSb.toString());
        meta.setTextSize(10f);
        meta.setTypeface(android.graphics.Typeface.MONOSPACE);
        meta.setTextColor(getColor(R.color.deck_text_dim));
        meta.setPadding(0, dp(4), 0, dp(6));
        card.addView(meta);

        // 3. INCOMING MESSAGE CONTAINER
        if (entry.incoming != null && !entry.incoming.trim().isEmpty()) {
            LinearLayout inBox = new LinearLayout(activity);
            inBox.setOrientation(LinearLayout.VERTICAL);
            inBox.setBackgroundResource(R.drawable.bg_cyber_row);
            inBox.setPadding(dp(9), dp(7), dp(9), dp(7));
            LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            inLp.bottomMargin = dp(6);
            inBox.setLayoutParams(inLp);

            TextView inLabel = new TextView(activity);
            inLabel.setText("📥 INCOMING");
            inLabel.setTextSize(9f);
            inLabel.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            inLabel.setTextColor(getColor(R.color.deck_accent));
            inBox.addView(inLabel);

            TextView inText = new TextView(activity);
            inText.setText("“" + entry.incoming.trim() + "”");
            inText.setTextSize(11f);
            inText.setTypeface(android.graphics.Typeface.MONOSPACE);
            inText.setTextColor(getColor(R.color.text_primary));
            inText.setTextIsSelectable(true);
            inText.setPadding(0, dp(2), 0, 0);
            inBox.addView(inText);

            card.addView(inBox);
        }

        // 4. DECISION & REASON BLOCK
        LinearLayout decisionBox = new LinearLayout(activity);
        decisionBox.setOrientation(LinearLayout.VERTICAL);
        decisionBox.setPadding(0, 0, 0, dp(4));

        ResponderGuards.Verdict verdict;
        try { verdict = ResponderGuards.Verdict.valueOf(entry.verdict); }
        catch (Exception e) { verdict = null; }
        String whyText = (entry.reason != null && !entry.reason.trim().isEmpty())
                ? entry.reason.trim()
                : ResponderGuards.explain(verdict);

        TextView decLabel = new TextView(activity);
        decLabel.setText(spoke ? "⚖️ VERDICT: RESPOND" : "⚖️ VERDICT: STAY SILENT (" + entry.verdict + ")");
        decLabel.setTextSize(9f);
        decLabel.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        decLabel.setTextColor(getColor(spoke ? R.color.mint : R.color.warning));
        decisionBox.addView(decLabel);

        TextView reasonTv = new TextView(activity);
        reasonTv.setText(whyText);
        reasonTv.setTextSize(11f);
        reasonTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        reasonTv.setTextColor(getColor(R.color.deck_text_dim));
        reasonTv.setPadding(0, dp(2), 0, 0);
        decisionBox.addView(reasonTv);

        card.addView(decisionBox);

        // 5. OUTGOING REPLY CONTAINER (IF SPOKE)
        if (spoke && entry.reply != null && !entry.reply.trim().isEmpty()) {
            LinearLayout outBox = new LinearLayout(activity);
            outBox.setOrientation(LinearLayout.VERTICAL);
            outBox.setBackgroundResource(R.drawable.bg_cyber_row_flagged);
            outBox.setPadding(dp(9), dp(7), dp(9), dp(7));
            LinearLayout.LayoutParams outLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            outLp.topMargin = dp(4);
            outLp.bottomMargin = dp(4);
            outBox.setLayoutParams(outLp);

            TextView outLabel = new TextView(activity);
            String outHeader = failed ? "⚠️ ATTEMPTED REPLY (DELIVERY FAILED)"
                    : sent ? "📤 SENT REPLY (DELIVERED TO APP)"
                    : "🧪 SIMULATED REPLY (DRY RUN)";
            outLabel.setText(outHeader);
            outLabel.setTextSize(9f);
            outLabel.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            outLabel.setTextColor(getColor(failed ? R.color.deck_error : sent ? R.color.mint : R.color.violet));
            outBox.addView(outLabel);

            TextView outText = new TextView(activity);
            outText.setText("“" + entry.reply.trim() + "”");
            outText.setTextSize(11f);
            outText.setTypeface(android.graphics.Typeface.MONOSPACE);
            outText.setTextColor(getColor(R.color.text_primary));
            outText.setTextIsSelectable(true);
            outText.setPadding(0, dp(2), 0, 0);
            outBox.addView(outText);

            if (failed) {
                NotificationReply.Result result;
                try { result = NotificationReply.Result.valueOf(entry.sendResult); }
                catch (Exception e) { result = null; }
                TextView failDetail = new TextView(activity);
                failDetail.setText("↳ " + NotificationReply.explain(result));
                failDetail.setTextSize(10f);
                failDetail.setTypeface(android.graphics.Typeface.MONOSPACE);
                failDetail.setTextColor(getColor(R.color.deck_error));
                failDetail.setPadding(0, dp(4), 0, 0);
                outBox.addView(failDetail);
            }

            card.addView(outBox);
        }

        // 6. FOOTER HINT
        TextView footerHint = new TextView(activity);
        footerHint.setText("ℹ️ Tap to view full audit  ·  Long press for actions");
        footerHint.setTextSize(9f);
        footerHint.setTypeface(android.graphics.Typeface.MONOSPACE);
        footerHint.setTextColor(getColor(R.color.border_accent));
        footerHint.setPadding(0, dp(4), 0, 0);
        card.addView(footerHint);

        // Single tap to view audit breakdown immediately
        card.setOnClickListener(v -> showDecisionWhyDialog(entry));

        // Long press opens context action menu
        final String who2 = entry.sender;
        card.setOnLongClickListener(v -> {
            java.util.List<String> items = new java.util.ArrayList<>();
            items.add("Why was this decision taken?");
            items.add("Delete this entry");
            items.add("\uD83D\uDCCC Pin to assistant queue");
            items.add("\uD83D\uDEA9 Flag as urgent");
            boolean hasSender = who2 != null && !who2.trim().isEmpty();
            if (hasSender) items.add("Teach a reply for " + who2);
            if (hasSender) items.add("Set a rule for " + who2);
            new AlertDialog.Builder(activity)
                    .setTitle(hasSender ? who2 : "This decision")
                    .setItems(items.toArray(new String[0]), (d, which) -> {
                        String choice = items.get(which);
                        if (choice.equals("Why was this decision taken?")) {
                            showDecisionWhyDialog(entry);
                        } else if (choice.equals("Delete this entry")) {
                            ResponderLog log2 = new ResponderLog(activity);
                            final java.util.List<ResponderLog.Entry> before = log2.all();
                            log2.deleteOne(entry.whenMs, entry.sender, entry.verdict);
                            responderLogSignature = log2.signature();
                            renderResponderLog(root);
                            new AlertDialog.Builder(activity)
                                     .setMessage("Removed one decision from the log.")
                                    .setNegativeButton("Undo", (dd, ww) -> {
                                        log2.overwrite(before);
                                        responderLogSignature = log2.signature();
                                        renderResponderLog(root);
                                    })
                                    .setPositiveButton("Keep removed", null)
                                    .show();
                        } else if (choice.contains("Pin to assistant queue")) {
                            FlaggedStore.get(activity).addPinned(
                                    entry.incoming, who2, entry.appLabel, "Pinned from decision log", System.currentTimeMillis());
                            toast("Pinned to assistant queue");
                        } else if (choice.contains("Flag as urgent")) {
                            FlaggedStore.get(activity).addFlagged(
                                    entry.incoming, who2, entry.appLabel, "Flagged from decision log", System.currentTimeMillis());
                            toast("Flagged as urgent");
                        } else if (choice.startsWith("Teach a reply")) {
                            teachReplyDialog(entry, root);
                        } else {
                            editPhoneContact(new ResponderSettings(activity), who2, root, null);
                        }
                    }).show();
            return true;
        });

        return card;
    }

    private void showDecisionWhyDialog(final ResponderLog.Entry entry) {
        StringBuilder sb = new StringBuilder();
        if (entry.incoming != null && !entry.incoming.trim().isEmpty()) {
            sb.append("📥 INCOMING MESSAGE:\n\"").append(entry.incoming.trim()).append("\"\n\n");
        }
        sb.append("👤 SENDER: ").append(entry.sender.isEmpty() ? "Unknown" : entry.sender);
        if (entry.group) sb.append(" (group)");
        sb.append("\n📱 APP: ").append(entry.appLabel.isEmpty() ? entry.pkg : entry.appLabel).append("\n");
        sb.append("🏷️ CATEGORY: ").append(entry.category).append("  |  🎭 TONE: ").append(entry.tone).append("\n");
        sb.append("⚖️ VERDICT: ").append(entry.verdict);
        if (entry.dryRun) sb.append(" (DRY RUN)");
        sb.append("\n\n");

        if (entry.wouldHaveSent()) {
            String outHeader = entry.sendFailed() ? "⚠️ FAILED REPLY:" : (entry.actuallySent() ? "📤 SENT REPLY:" : "🧪 SIMULATED REPLY (DRY RUN):");
            sb.append(outHeader).append("\n\"").append(entry.reply).append("\"\n\n");
        }

        sb.append("🎯 DECISION RATIONALE:\n");
        if (entry.reason != null && !entry.reason.trim().isEmpty()) {
            sb.append(entry.reason.trim());
        } else {
            ResponderGuards.Verdict v;
            try { v = ResponderGuards.Verdict.valueOf(entry.verdict); } catch (Exception e) { v = null; }
            sb.append(ResponderGuards.explain(v));
        }

        final String copyText = sb.toString();
        new AlertDialog.Builder(activity)
                .setTitle("Why this decision was taken")
                .setMessage(copyText)
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Decision Details", copyText));
                        toast("Copied decision details");
                    }
                })
                .show();
    }

    /**
     * Teach IRIS a reply for this sender and this kind of message, from the decision log. The
     * learned reply outranks name rules, smart replies and templates next time. Device-
     * authenticated, because it changes what IRIS says on the owner's behalf.
     */
    private void teachReplyDialog(final ResponderLog.Entry entry, final View root) {
        final EditText input = new EditText(activity);
        input.setHint("what I should reply to " + entry.sender + " for messages like this");
        if (entry.reply != null && !entry.reply.isEmpty()) input.setText(entry.reply);
        input.setMinLines(2);
        new AlertDialog.Builder(activity)
                .setTitle("Teach a reply")
                .setMessage("I'll use this for " + entry.sender + " when a message is "
                        + entry.category.toLowerCase(java.util.Locale.ROOT) + ". You can use "
                        + "{name}, {time} and {part_of_day}.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> authenticateThen("Teach IRIS a reply", () -> {
                    String text = input.getText().toString().trim();
                    if (text.isEmpty()) {
                        toast("Nothing to save.");
                        return;
                    }
                    new LearnedReplyStore(activity).teach(entry.sender, entry.category, text);
                    toast("Learned \u2014 I'll use that for " + entry.sender + ".");
                    if (!ReplyTemplates.identifiesIris(text)) {
                        new AlertDialog.Builder(activity)
                                .setTitle("Saved, but they'll think it was you")
                                .setMessage("That reply names neither me nor you, so whoever "
                                        + "matches it will assume you typed it yourself. Saved as "
                                        + "written.")
                                .setPositiveButton("I know", null)
                                .show();
                    }
                }))
                .show();
    }

}
