package com.iris.assistant;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Controller for the Contacts section in Comms Deck.
 * Manages phone contacts browsing, allow/never rules, relationship tagging, and reply tone selection.
 */
public class ContactsViewController {

    private MainActivity activity;
    private View rootView;

    public void bind(MainActivity activity, View view) {
        this.activity = activity;
        this.rootView = view;
        bindContactsSection(view);
    }

    private int dp(int value) {
        if (activity == null) return value;
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        if (activity != null) activity.toast(message);
    }

    public void bindContactsSection(final View view) {
        EditText search = view.findViewById(R.id.contactsSearch);
        if (search != null) {
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable s) {
                    renderContacts(view);
                }
            });
        }
        View add = view.findViewById(R.id.contactsAddButton);
        if (add != null) add.setOnClickListener(v -> addContactByHand(view));
        View browse = view.findViewById(R.id.contactsBrowseButton);
        if (browse != null) browse.setOnClickListener(v -> browsePhoneContacts(view));
        renderContacts(view);
    }

    public void browsePhoneContacts(final View root) {
        if (!DeviceContacts.canRead(activity)) {
            new AlertDialog.Builder(activity)
                    .setTitle("I can't read your contacts")
                    .setMessage("Grant contacts access and I can list them here for you to pick "
                            + "from. Until then, you can still add someone by name with the "
                            + "button above.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);

        final EditText search = new EditText(activity);
        search.setHint("search your contacts");
        search.setMaxLines(1);
        box.addView(search);

        final LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        final ScrollView scroller = new ScrollView(activity);
        scroller.addView(list);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360));
        scroller.setLayoutParams(sp);
        box.addView(scroller);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Phone contacts")
                .setView(box)
                .setPositiveButton("Done", (d, w) -> renderContacts(root))
                .create();

        final ResponderSettings settings = new ResponderSettings(activity);
        final Runnable repopulate = () -> {
            list.removeAllViews();
            List<String> names = DeviceContacts.search(activity, search.getText().toString());
            if (names.isEmpty()) {
                TextView empty = new TextView(activity);
                empty.setText("No contacts by that name.");
                empty.setTextSize(11f);
                empty.setTypeface(Typeface.MONOSPACE);
                empty.setTextColor(activity.getColor(R.color.deck_text_dim));
                empty.setPadding(0, dp(10), 0, dp(10));
                list.addView(empty);
                return;
            }
            for (final String name : names) {
                list.addView(phoneContactCard(settings, name, root, dialog));
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { repopulate.run(); }
        });
        repopulate.run();
        dialog.show();
    }

    private View phoneContactCard(final ResponderSettings settings, final String name,
                                  final View root, final AlertDialog browseDialog) {
        ResponderGuards.ContactRule rule = settings.nameContactRule(name);
        ReplyTone.Relation relation = settings.nameContactRelation(name);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(11), dp(9), dp(11), dp(9));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(5);
        card.setLayoutParams(params);
        card.setBackgroundResource(rule == ResponderGuards.ContactRule.NEVER
                ? R.drawable.bg_cyber_row_flagged : R.drawable.bg_cyber_row);

        TextView label = new TextView(activity);
        label.setText(name);
        label.setTextSize(12f);
        label.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        label.setTextColor(activity.getColor(rule == ResponderGuards.ContactRule.NEVER
                ? R.color.deck_error : R.color.deck_accent));
        card.addView(label);

        if (rule != ResponderGuards.ContactRule.INHERIT
                || relation != ReplyTone.Relation.UNKNOWN) {
            TextView state = new TextView(activity);
            StringBuilder detail = new StringBuilder();
            detail.append(rule == ResponderGuards.ContactRule.INHERIT ? "default" : rule.name());
            if (relation != ReplyTone.Relation.UNKNOWN) {
                detail.append(" · ").append(relation.name());
            }
            state.setText(detail.toString());
            state.setTextSize(10f);
            state.setTypeface(Typeface.MONOSPACE);
            state.setTextColor(activity.getColor(R.color.deck_text_dim));
            state.setPadding(0, dp(3), 0, 0);
            card.addView(state);
        }

        card.setOnClickListener(v -> editPhoneContact(settings, name, root, browseDialog));
        return card;
    }

    public void editPhoneContact(final ResponderSettings settings, final String name,
                                  final View root, final AlertDialog browseDialog) {
        new AlertDialog.Builder(activity)
                .setTitle(name)
                .setItems(new String[]{
                        "ALLOW — answer them",
                        "NEVER — never answer them",
                        "Default — follow my general rules",
                        "They're family",
                        "They're a work contact",
                        "They're a friend",
                        "Clear who they are"
                }, (d, which) -> {
                    switch (which) {
                        case 0:
                            settings.setNameContactRule(name, ResponderGuards.ContactRule.ALLOW);
                            break;
                        case 1:
                            settings.setNameContactRule(name, ResponderGuards.ContactRule.NEVER);
                            toast("Noted. Not a word to " + name + ".");
                            break;
                        case 2:
                            settings.setNameContactRule(name, ResponderGuards.ContactRule.INHERIT);
                            break;
                        case 3:
                            settings.setNameContactRelation(name, ReplyTone.Relation.FAMILY);
                            break;
                        case 4:
                            settings.setNameContactRelation(name, ReplyTone.Relation.WORK);
                            break;
                        case 5:
                            settings.setNameContactRelation(name, ReplyTone.Relation.FRIEND);
                            break;
                        default:
                            settings.setNameContactRelation(name, ReplyTone.Relation.UNKNOWN);
                            break;
                    }
                    renderContacts(root);
                }).show();
    }

    public void addContactByHand(final View root) {
        final ResponderSettings settings = new ResponderSettings(activity);
        final List<String> packages = new ArrayList<>(settings.apps());
        if (packages.isEmpty()) {
            toast("Pick the apps I may speak through first, on the RESPONDER tab.");
            return;
        }

        final EditText name = new EditText(activity);
        name.setHint("name exactly as it appears in the notification");
        name.setMaxLines(1);

        final String[] appLabels = new String[packages.size()];
        for (int i = 0; i < packages.size(); i++) {
            appLabels[i] = appLabel(packages.get(i));
        }
        final int[] chosen = {0};

        final Button appButton = new Button(activity);
        appButton.setText("App: " + appLabels[0]);
        appButton.setOnClickListener(v -> new AlertDialog.Builder(activity)
                .setTitle("Which app?")
                .setItems(appLabels, (d, which) -> {
                    chosen[0] = which;
                    appButton.setText("App: " + appLabels[which]);
                }).show());

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        box.addView(name);
        box.addView(appButton);

        new AlertDialog.Builder(activity)
                .setTitle("Who should I know about?")
                .setMessage("A rule is stored against the name as the app shows it, so spell it "
                        + "the way the notification does. Get it wrong and the rule simply never "
                        + "matches — it won't warn you.")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (d, w) -> {
                    String typed = name.getText().toString().trim();
                    if (typed.isEmpty()) {
                        toast("I need a name to attach the rule to.");
                        return;
                    }
                    String key = ResponderSettings.conversationKey(packages.get(chosen[0]), typed);
                    settings.setContactLabel(key, typed + "  ·  " + appLabels[chosen[0]]);
                    settings.setContactRule(key, ResponderGuards.ContactRule.INHERIT);
                    renderContacts(root);
                    editContact(settings, key, typed, root);
                }).show();
    }

    private String appLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = activity.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable ignored) {
            return pkg;
        }
    }

    public void renderContacts(View view) {
        final ResponderSettings settings = new ResponderSettings(activity);
        LinearLayout list = view.findViewById(R.id.contactsList);
        TextView summary = view.findViewById(R.id.contactsSummary);
        EditText search = view.findViewById(R.id.contactsSearch);
        if (list == null) return;

        String term = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);

        LinkedHashMap<String, String> rows = new LinkedHashMap<>();
        for (ResponderLog.Entry entry : new ResponderLog(activity).distinctSenders()) {
            String name = ContactResolver.display(activity, entry.sender);
            if (name.trim().isEmpty()) continue;
            rows.put(ResponderSettings.conversationKey(entry.pkg, name),
                    name + "  ·  " + entry.appLabel);
        }
        for (NotificationArchive.SenderRef ref : NotificationArchive.recentSenders(activity, 120)) {
            String name = ContactResolver.display(activity, ref.sender);
            if (name.trim().isEmpty()) continue;
            String key = ResponderSettings.conversationKey(ref.pkg, name);
            if (!rows.containsKey(key)) rows.put(key, name + "  ·  " + ref.appLabel);
        }
        for (String key : settings.knownContactKeys()) {
            String label = settings.contactLabel(key);
            if (!label.isEmpty() && !rows.containsValue(label)) rows.put(key, label);
        }

        int configured = 0;
        for (String key : rows.keySet()) {
            if (settings.contactRule(key) != ResponderGuards.ContactRule.INHERIT) configured++;
        }
        if (summary != null) {
            summary.setText(rows.isEmpty()
                    ? "Nobody yet. Once I've seen some messages, the people who sent them appear "
                      + "here and you can tell me who I may answer."
                    : rows.size() + " people I've seen · " + configured
                      + " with your explicit instructions"
                      + (settings.allowlistOnly()
                            ? "\nAllow-list mode is on, so I answer ONLY the ones marked ALLOW."
                            : "\nI answer anyone eligible except the ones marked NEVER."));
        }

        list.removeAllViews();
        int shown = 0;
        for (Map.Entry<String, String> entry : rows.entrySet()) {
            if (!term.isEmpty() && !entry.getValue().toLowerCase(Locale.ROOT).contains(term)) {
                continue;
            }
            list.addView(contactCard(settings, entry.getKey(), entry.getValue(), view));
            shown++;
        }
        if (shown == 0 && !rows.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("Nobody by that name.");
            empty.setTextSize(11f);
            empty.setTypeface(Typeface.MONOSPACE);
            empty.setTextColor(activity.getColor(R.color.deck_text_dim));
            empty.setPadding(0, dp(12), 0, dp(12));
            list.addView(empty);
        }
    }

    private View contactCard(final ResponderSettings settings, final String key,
                             final String label, final View root) {
        ResponderGuards.ContactRule rule = settings.contactRule(key);
        ReplyTone.Relation relation = settings.contactRelation(key);
        ReplyTone.Tone tone = settings.contactTone(key);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(11), dp(9), dp(11), dp(9));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(5);
        card.setLayoutParams(params);
        card.setBackgroundResource(rule == ResponderGuards.ContactRule.NEVER
                ? R.drawable.bg_cyber_row_flagged : R.drawable.bg_cyber_row);

        TextView name = new TextView(activity);
        name.setText(label);
        name.setTextSize(12f);
        name.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        name.setTextColor(activity.getColor(rule == ResponderGuards.ContactRule.NEVER
                ? R.color.deck_error : R.color.deck_accent));
        card.addView(name);

        TextView state = new TextView(activity);
        StringBuilder detail = new StringBuilder();
        detail.append(rule == ResponderGuards.ContactRule.INHERIT ? "default" : rule.name());
        if (relation != ReplyTone.Relation.UNKNOWN) detail.append(" · ").append(relation.name());
        detail.append(" · ").append(tone == null ? "tone: default" : "tone: " + tone.name());
        state.setText(detail.toString());
        state.setTextSize(10f);
        state.setTypeface(Typeface.MONOSPACE);
        state.setTextColor(activity.getColor(R.color.deck_text_dim));
        state.setPadding(0, dp(3), 0, 0);
        card.addView(state);

        card.setOnClickListener(v -> editContact(settings, key, label, root));
        return card;
    }

    private void editContact(final ResponderSettings settings, final String key,
                             final String label, final View root) {
        settings.setContactLabel(key, label);
        new AlertDialog.Builder(activity)
                .setTitle(label)
                .setItems(new String[]{
                        "ALLOW — answer them",
                        "NEVER — never answer them, whatever happens",
                        "Default — follow my general rules",
                        "Who are they? (family, work, friend)",
                        "How should I sound to them?"
                }, (d, which) -> {
                    switch (which) {
                        case 0:
                            settings.setContactRule(key, ResponderGuards.ContactRule.ALLOW);
                            break;
                        case 1:
                            settings.setContactRule(key, ResponderGuards.ContactRule.NEVER);
                            toast("Understood. Not a word to them, ever.");
                            break;
                        case 2:
                            settings.setContactRule(key, ResponderGuards.ContactRule.INHERIT);
                            break;
                        case 3:
                            editContactRelation(settings, key, label, root);
                            return;
                        default:
                            editContactTone(settings, key, label, root);
                            return;
                    }
                    renderContacts(root);
                }).show();
    }

    public void editContactRelation(final ResponderSettings settings, final String key,
                                   final String label, final View root) {
        final ReplyTone.Relation[] relations = ReplyTone.Relation.values();
        String[] labels = new String[relations.length];
        for (int i = 0; i < relations.length; i++) {
            labels[i] = relations[i] == ReplyTone.Relation.UNKNOWN
                    ? "Not saying — use my general rules" : relations[i].name();
        }
        new AlertDialog.Builder(activity)
                .setTitle("Who is " + label + "?")
                .setMessage("This decides how I speak to them unless you overrule it. Family and "
                        + "friends get the relaxed version; work gets the careful one. It also "
                        + "beats my name-matching rules — so if someone called Erica is "
                        + "family, she stops being mistaken for an Ericsson colleague.")
                .setItems(labels, (d, which) -> {
                    settings.setContactRelation(key, relations[which]);
                    renderContacts(root);
                }).show();
    }

    public void editContactTone(final ResponderSettings settings, final String key,
                               final String label, final View root) {
        final ReplyTone.Tone[] tones = ReplyTone.Tone.values();
        String[] labels = new String[tones.length + 1];
        for (int i = 0; i < tones.length; i++) {
            labels[i] = tones[i] == ReplyTone.Tone.SARCASTIC
                    ? "SARCASTIC — they'd better deserve it" : tones[i].name();
        }
        labels[tones.length] = "Clear it — work it out yourself";
        new AlertDialog.Builder(activity)
                .setTitle("How should I sound to " + label + "?")
                .setItems(labels, (d, which) -> {
                    settings.setContactTone(key, which < tones.length ? tones[which] : null);
                    renderContacts(root);
                }).show();
    }
}
