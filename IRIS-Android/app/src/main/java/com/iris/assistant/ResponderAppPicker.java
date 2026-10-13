package com.iris.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Chooses which apps the responder may send messages through.
 *
 * Separate from ArchiveAppPicker, and deliberately so. The archive's list answers "keep a record
 * of this app"; this one answers "send messages through this app on my behalf". Sharing one list
 * would mean ticking Instagram to catch deleted DMs silently authorised outgoing Instagram
 * messages, which is not what that tick meant.
 *
 * Ships with nothing selected. The responder is the one feature here that acts on the owner's
 * behalf, so every app it may speak through is an explicit decision.
 */
public final class ResponderAppPicker {

    private ResponderAppPicker() { }

    private static final class Entry {
        final String pkg;
        final String label;
        Entry(String pkg, String label) { this.pkg = pkg; this.label = label; }
    }

    public static void show(final Activity activity, final Runnable onSaved) {
        if (activity == null) return;
        final ResponderSettings settings = new ResponderSettings(activity);
        final Set<String> ticked = new LinkedHashSet<>(settings.apps());

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        root.setPadding(pad, pad, pad, 0);

        TextView blurb = new TextView(activity);
        blurb.setText("These are the apps I'm allowed to speak through, Suvra. Nothing is ticked "
                + "until you tick it.\n\nThis is a separate list from the archive's on purpose \u2014 "
                + "letting me keep records of an app is not the same as letting me send messages "
                + "through it.\n\nApps that give me no way to reply are shown greyed out. I can't "
                + "answer through those however much you'd like me to.");
        blurb.setTextSize(12f);
        root.addView(blurb);

        final LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Who may I answer for you?")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    settings.setApps(ticked);
                    Toast.makeText(activity, ticked.isEmpty()
                                    ? "Nothing ticked. I'll stay quiet everywhere."
                                    : "I may speak through " + ticked.size()
                                            + (ticked.size() == 1 ? " app." : " apps."),
                            Toast.LENGTH_LONG).show();
                    if (onSaved != null) onSaved.run();
                })
                .create();

        // Scanned off the main thread: getInstalledApplications is slow enough to stall the
        // dialog opening on a phone with a lot of apps.
        new Thread(() -> {
            final List<Entry> entries = scan(activity);
            activity.runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                render(activity, list, entries, ticked);
            });
        }, "IRIS-ResponderApps").start();

        dialog.show();
    }

    private static List<Entry> scan(Activity activity) {
        List<Entry> out = new ArrayList<>();
        try {
            PackageManager manager = activity.getPackageManager();
            for (ApplicationInfo info : manager.getInstalledApplications(0)) {
                if (info.packageName == null) continue;
                if (info.packageName.equals(activity.getPackageName())) continue;
                // Only apps that can actually be launched; system plumbing cannot be messaged.
                if (manager.getLaunchIntentForPackage(info.packageName) == null) continue;
                String label = info.packageName;
                try { label = manager.getApplicationLabel(info).toString(); }
                catch (Throwable ignored) { }
                out.add(new Entry(info.packageName, label));
            }
        } catch (Throwable ignored) { }

        // Messaging apps first, then alphabetical. The owner is looking for WhatsApp, not for a
        // calculator.
        java.util.Collections.sort(out, (a, b) -> {
            boolean messagingA = ArchiveAppTargets.isMessagingCandidate(a.pkg, a.label);
            boolean messagingB = ArchiveAppTargets.isMessagingCandidate(b.pkg, b.label);
            if (messagingA != messagingB) return messagingA ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        return out;
    }

    private static void render(final Activity activity, LinearLayout list,
                               List<Entry> entries, final Set<String> ticked) {
        list.removeAllViews();
        Set<String> replyCapable = IrisNotificationListener.packagesSeenWithReplyAction();

        for (final Entry entry : entries) {
            // Known capable, or not yet observed. An app is only greyed out once it has posted a
            // notification that offered no reply action, so an app simply not seen yet is not
            // wrongly presented as impossible.
            boolean known = replyCapable.contains(entry.pkg);
            CheckBox box = new CheckBox(activity);
            box.setText(entry.label + (known ? "  \u2713 can reply" : ""));
            box.setTextSize(13f);
            box.setMinHeight(dp(activity, 44));
            box.setChecked(ticked.contains(entry.pkg));
            box.setOnCheckedChangeListener((button, checked) -> {
                if (checked) ticked.add(entry.pkg);
                else ticked.remove(entry.pkg);
            });
            list.addView(box);
        }

        if (entries.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("I couldn't read your app list.");
            list.addView(empty);
        }
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }
}
