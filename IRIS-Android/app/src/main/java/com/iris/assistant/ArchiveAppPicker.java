package com.iris.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Chooses which apps the notification archive captures.
 *
 * WHY MESSAGING APPS ARE SURFACED FIRST: a real phone has two to four hundred installed packages.
 * An alphabetical list would bury WhatsApp and Instagram somewhere in the middle, so the apps this
 * feature exists for are grouped at the top and everything else follows.
 *
 * WHY LOADING HAPPENS OFF THE MAIN THREAD: resolving a label and icon for several hundred packages
 * takes long enough to be visible as a stall. The dialog opens immediately with a loading line and
 * fills in when the scan finishes.
 *
 * SELECTIONS ARE ONLY SAVED ON SAVE. Toggling a box does nothing until the owner confirms, so a
 * mis-tap while scrolling cannot silently start archiving somebody's private messages.
 */
public final class ArchiveAppPicker {

    private ArchiveAppPicker() { }

    private static final class Entry {
        final String pkg;
        final String label;
        final boolean messaging;

        Entry(String pkg, String label, boolean messaging) {
            this.pkg = pkg;
            this.label = label;
            this.messaging = messaging;
        }
    }

    public static void show(final Activity activity) {
        show(activity, null);
    }

    /**
     * @param onSaved run after the owner saves, so the caller can re-capture and refresh. Without
     *                it, ticking an app appears to do nothing until the next notification arrives.
     */
    public static void show(final Activity activity, final Runnable onSaved) {
        if (activity == null) return;
        final AppSettings settings = new AppSettings(activity);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        root.setPadding(pad, pad, pad, 0);

        TextView blurb = new TextView(activity);
        blurb.setText("Tick an app and I watch it. Leave it unticked and I am gloriously "
                + "indifferent to it.\n\nSave to commit. This only applies to notifications that "
                + "arrive from now on \u2014 I cannot retrieve what I was never shown, STG.");
        blurb.setTextSize(12f);
        root.addView(blurb);

        final EditText search = new EditText(activity);
        search.setHint("Search apps");
        search.setSingleLine(true);
        search.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchParams.topMargin = dp(activity, 10);
        root.addView(search, searchParams);

        final TextView status = new TextView(activity);
        status.setText("Loading installed apps\u2026");
        status.setTextSize(12f);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(activity, 8);
        root.addView(status, statusParams);

        final LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 360));
        scrollParams.topMargin = dp(activity, 6);
        root.addView(scroll, scrollParams);

        // Live set of ticked packages, seeded from the saved choice or the first-run defaults.
        final Set<String> ticked = new LinkedHashSet<>();
        if (settings.archivedAppsChosen()) {
            ticked.addAll(settings.archivedApps());
        } else {
            Collections.addAll(ticked, ArchiveAppTargets.defaultTargets());
        }

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Apps to archive")
                .setView(root)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    settings.setArchivedApps(ticked);
                    // Pull in anything already on screen from the newly ticked apps, so the choice
                    // has a visible effect instead of appearing to do nothing.
                    IrisNotificationListener.captureVisibleNow();
                    Toast.makeText(activity, ticked.isEmpty()
                            ? "Nothing ticked. I shall watch nothing, intently."
                            : "Now watching " + ticked.size() + (ticked.size() == 1 ? " app." : " apps."),
                            Toast.LENGTH_LONG).show();
                    if (onSaved != null) onSaved.run();
                })
                .create();
        dialog.show();

        // Scan packages off the main thread; a few hundred label lookups is a visible stall.
        new Thread(() -> {
            final List<Entry> entries = scan(activity);
            activity.runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                int messaging = 0;
                for (Entry entry : entries) if (entry.messaging) messaging++;
                status.setText(entries.size() + " apps found \u00b7 " + messaging
                        + " look like messaging apps and are listed first.");
                render(activity, list, entries, ticked, "");
                search.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                    @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                    @Override public void afterTextChanged(Editable s) {
                        render(activity, list, entries, ticked, s.toString());
                    }
                });
            });
        }, "iris-app-scan").start();
    }

    /**
     * Installed applications, messaging candidates first and alphabetical within each group.
     *
     * IRIS itself is excluded: its own notifications are already skipped during capture, and
     * offering it would only invite confusion.
     */
    private static List<Entry> scan(Activity activity) {
        List<Entry> entries = new ArrayList<>();
        try {
            PackageManager pm = activity.getPackageManager();
            List<ApplicationInfo> installed = pm.getInstalledApplications(0);
            if (installed == null) return entries;
            String self = activity.getPackageName();
            for (ApplicationInfo info : installed) {
                if (info == null || info.packageName == null) continue;
                if (info.packageName.equals(self)) continue;
                String label;
                try {
                    label = String.valueOf(pm.getApplicationLabel(info));
                } catch (Throwable t) {
                    label = info.packageName;
                }
                entries.add(new Entry(info.packageName, label,
                        ArchiveAppTargets.isMessagingCandidate(info.packageName, label)));
            }
            Collections.sort(entries, new Comparator<Entry>() {
                @Override public int compare(Entry a, Entry b) {
                    if (a.messaging != b.messaging) return a.messaging ? -1 : 1;
                    return a.label.compareToIgnoreCase(b.label);
                }
            });
        } catch (Throwable ignored) { }
        return entries;
    }

    private static void render(Activity activity, LinearLayout list, List<Entry> entries,
                               final Set<String> ticked, String query) {
        list.removeAllViews();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.US);
        boolean headerAdded = false;
        boolean otherHeaderAdded = false;
        int shown = 0;

        for (final Entry entry : entries) {
            if (!needle.isEmpty()
                    && !entry.label.toLowerCase(Locale.US).contains(needle)
                    && !entry.pkg.toLowerCase(Locale.US).contains(needle)) {
                continue;
            }
            // Headers are only drawn when not searching; mid-search they are noise.
            if (needle.isEmpty() && entry.messaging && !headerAdded) {
                list.addView(header(activity, "MESSAGING APPS"));
                headerAdded = true;
            }
            if (needle.isEmpty() && !entry.messaging && !otherHeaderAdded) {
                list.addView(header(activity, "EVERYTHING ELSE"));
                otherHeaderAdded = true;
            }

            CheckBox box = new CheckBox(activity);
            box.setText(entry.label + "\n" + entry.pkg);
            box.setTextSize(13f);
            box.setMinHeight(dp(activity, 48));
            box.setChecked(ticked.contains(entry.pkg));
            box.setOnCheckedChangeListener((button, checked) -> {
                if (checked) ticked.add(entry.pkg); else ticked.remove(entry.pkg);
            });
            list.addView(box);
            shown++;
        }

        if (shown == 0) {
            TextView empty = new TextView(activity);
            empty.setText("No apps match that search.");
            empty.setTextSize(13f);
            empty.setPadding(0, dp(activity, 12), 0, dp(activity, 12));
            list.addView(empty);
        }
    }

    private static TextView header(Activity activity, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(11f);
        view.setPadding(0, dp(activity, 12), 0, dp(activity, 4));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
