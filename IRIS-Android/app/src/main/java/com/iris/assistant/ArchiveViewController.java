package com.iris.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Controller for the Notification Vault / Archive deck.
 *
 * Responsibilities:
 * - Archive list rendering, pagination, and multi-mode views (Timeline vs Conversations).
 * - Filtering (presets, date ranges, custom picker, app targets).
 * - Media handling: photo lightbox (pan/zoom), video player, waveform voice note player, doc preview.
 * - Swipe-to-delete queue with undo bar and batch deletion commit.
 * - Forensic exports (ZIP backup with media, HTML dark-mode report, CSV, plain text).
 * - Multi-store wipe-all coordinator delegation.
 */
public class ArchiveViewController {

    public static final int EXPORT_ARCHIVE_CSV = 207;
    public static final int EXPORT_ARCHIVE_TEXT = 208;
    public static final int SAVE_ARCHIVE_IMAGE = 209;
    public static final int EXPORT_ARCHIVE_ZIP = 210;
    public static final int EXPORT_ARCHIVE_HTML = 211;

    private MainActivity activity;
    private View rootView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public void bind(MainActivity activity, View root) {
        this.activity = activity;
        this.rootView = root;
        bindArchiveSection(root);
    }

    private <T extends View> T findViewById(int id) {
        if (rootView != null) {
            T v = rootView.findViewById(id);
            if (v != null) return v;
        }
        return activity != null ? activity.findViewById(id) : null;
    }

    private boolean isFinishing() {
        return activity == null || activity.isFinishing();
    }

    private boolean isDestroyed() {
        return activity == null || activity.isDestroyed();
    }

    public void flushDeletes() {
        flushArchiveDeletes();
    }

    public void stopAudioPlayback() {
        stopArchiveAudioPlayback();
    }

    public boolean handleBackPressed() {
        if (archiveCurrentThreadTitle != null) {
            archiveCurrentThreadTitle = null;
            archiveCurrentThreadPkg = null;
            if (rootView != null) {
                updateArchiveViewModeButtons(rootView);
                if (archiveSearchRunnable != null) handler.removeCallbacks(archiveSearchRunnable);
                renderArchive(rootView);
            }
            return true;
        }
        return false;
    }

    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != SAVE_ARCHIVE_IMAGE && requestCode != EXPORT_ARCHIVE_CSV
                && requestCode != EXPORT_ARCHIVE_TEXT && requestCode != EXPORT_ARCHIVE_ZIP
                && requestCode != EXPORT_ARCHIVE_HTML) {
            return false;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }
        Uri uri = data.getData();
        if (requestCode == SAVE_ARCHIVE_IMAGE) {
            writeArchiveImage(uri);
        } else {
            writeArchiveExport(uri, requestCode);
        }
        return true;
    }

    public void onDestroy() {
        stopArchiveAudioPlayback();
        if (archiveSearchRunnable != null) {
            handler.removeCallbacks(archiveSearchRunnable);
            archiveSearchRunnable = null;
        }
        if (archiveQueryExecutor != null) {
            archiveQueryExecutor.shutdown();
        }
        archiveFooterView = null;
        archiveFooterText = null;
        archiveHasMore = false;
        archiveIsLoadingMore = false;
        rootView = null;
        activity = null;
    }

    private int dp(int value) {
        if (activity == null) return value;
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        if (activity != null) activity.toast(message);
    }

    private int getColor(int resId) {
        if (activity == null) return 0;
        return activity.getColor(resId);
    }

    private Object getSystemService(String name) {
        return activity != null ? activity.getSystemService(name) : null;
    }

    private android.content.ContentResolver getContentResolver() {
        return activity != null ? activity.getContentResolver() : null;
    }

    private void startActivity(Intent intent) {
        if (activity != null) activity.startActivity(intent);
    }

    private void startActivityForResult(Intent intent, int requestCode) {
        if (activity != null) activity.startActivityForResult(intent, requestCode);
    }

    private void runOnUiThread(Runnable r) {
        if (activity != null) activity.runOnUiThread(r);
        else handler.post(r);
    }

    private boolean notificationAccessGranted() {
        return activity != null && activity.notificationAccessGranted();
    }

    private void openNotificationAccessSettings() {
        if (activity != null) activity.openNotificationAccessSettings();
    }

    private void showLogs() {
        if (activity != null) activity.showLogs();
    }

    private void writeText(Uri uri, String text) throws Exception {
        if (getContentResolver() == null) throw new IllegalStateException("Context not attached");
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IllegalStateException("Could not open destination");
            output.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private void writeArchiveExport(Uri uri, int requestCode) {
        final int req = requestCode;
        final NotificationArchive.Filter queryFilter;
        if (pendingExportFilter != null) {
            queryFilter = pendingExportFilter;
            queryFilter.limit = Integer.MAX_VALUE;
            pendingExportFilter = null;
        } else {
            queryFilter = new NotificationArchive.Filter();
            queryFilter.includeMarkers = true;
            queryFilter.limit = Integer.MAX_VALUE;
        }

        new Thread(() -> {
            try {
                Context ctx = activity != null ? activity.getApplicationContext() : null;
                if (ctx == null) return;
                java.util.List<ArchiveRow> rows =
                        NotificationArchive.query(ctx, queryFilter);
                java.util.TimeZone zone = java.util.TimeZone.getDefault();

                if (req == EXPORT_ARCHIVE_ZIP) {
                    try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                        ArchiveExport.writeZip(out, rows, zone, storedName -> {
                            java.io.File f = ArchiveImages.fileFor(ctx, storedName);
                            return (f != null && f.exists()) ? new java.io.FileInputStream(f) : null;
                        });
                    }
                } else if (req == EXPORT_ARCHIVE_HTML) {
                    String html = ArchiveExport.toHtmlReport(rows, System.currentTimeMillis(), zone, null);
                    writeText(uri, html);
                } else if (req == EXPORT_ARCHIVE_CSV) {
                    String body = ArchiveExport.toCsv(rows, zone);
                    writeText(uri, body);
                } else {
                    String body = ArchiveExport.toText(rows, System.currentTimeMillis(), zone);
                    writeText(uri, body);
                }

                AppSettings settings = new AppSettings(ctx);
                settings.setLastArchiveExportAt(System.currentTimeMillis());
                settings.setArchiveRowsSinceExport(0);
                final int count = rows.size();
                final String formatName = req == EXPORT_ARCHIVE_ZIP ? "ZIP backup"
                        : (req == EXPORT_ARCHIVE_HTML ? "HTML report"
                        : (req == EXPORT_ARCHIVE_CSV ? "CSV" : "text transcript"));
                runOnUiThread(() -> toast("Exported " + formatName + " (" + count
                        + (count == 1 ? " entry)." : " entries).")));
            } catch (Exception error) {
                runOnUiThread(() -> toast("Archive export failed."));
            }
        }, "IRIS-ExportArchive").start();
    }

    private static final long ARCHIVE_SEARCH_DEBOUNCE_MS = 250L;
    private static final int ARCHIVE_PAGE_SIZE = 50;
    private Runnable archiveSearchRunnable;
    private int archiveQuickFilter = NotificationArchive.Filter.QUICK_ALL;
    private static final int DATE_PRESET_ALL = 0;
    private static final int DATE_PRESET_TODAY = 1;
    private static final int DATE_PRESET_YESTERDAY = 2;
    private static final int DATE_PRESET_THIS_WEEK = 3;
    private static final int DATE_PRESET_CUSTOM = 4;
    private int archiveDatePreset = DATE_PRESET_ALL;
    private long archiveCustomFromMs = 0L;
    private long archiveCustomToMs = 0L;
    private static final int ARCHIVE_VIEW_TIMELINE = 0;
    private static final int ARCHIVE_VIEW_CONVERSATIONS = 1;
    private int archiveViewMode = ARCHIVE_VIEW_TIMELINE;
    private String archiveCurrentThreadTitle = null;
    private String archiveCurrentThreadPkg = null;
    private static final int ARCHIVE_DENSITY_DETAILED = 0;
    private static final int ARCHIVE_DENSITY_COMPACT = 1;
    private int archiveDensityMode = ARCHIVE_DENSITY_DETAILED;
    private final java.util.Set<Long> archiveExpandedRowIds = new java.util.HashSet<>();
    private String archiveLastSignature = "";
    private NotificationArchive.Filter pendingExportFilter = null;
    private final ExecutorService archiveQueryExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "iris-archive-db");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
    private long archiveQuerySequence = 0L;
    private boolean archiveHasMore = false;
    private boolean archiveIsLoadingMore = false;
    private View archiveFooterView;
    private TextView archiveFooterText;

    /** Wire the ARCHIVE section. Unchanged behaviour; only the host view moved. */
    private void bindArchiveSection(View view) {
        ArchiveAlerts.clear(activity);

        AppSettings settings = new AppSettings(activity);
        settings.setLastDeletedCheckAt(System.currentTimeMillis());
        maybeShowArchiveNotice(settings);
        // Repair a dropped binding before reporting status, so using the app is enough to recover
        // capture instead of the owner having to toggle notification access by hand.
        IrisNotificationListener.ensureConnected(activity);
        // Capture is event-driven, so a freshly ticked app shows nothing until its next
        // notification. Pulling in what is on screen now gives immediate evidence it is working.
        View diagHeader = view.findViewById(R.id.archiveDiagHeader);
        View diagBody = view.findViewById(R.id.archiveDiagBody);
        TextView diagToggle = view.findViewById(R.id.archiveDiagToggle);
        if (diagHeader != null && diagBody != null) {
            diagHeader.setOnClickListener(v -> {
                boolean showing = diagBody.getVisibility() == View.VISIBLE;
                diagBody.setVisibility(showing ? View.GONE : View.VISIBLE);
                if (diagToggle != null) {
                    diagToggle.setText(showing ? "DIAG \u25BE" : "DIAG \u25B4");
                }
            });
        }

        EditText search = view.findViewById(R.id.archiveSearch);

        View back = view.findViewById(R.id.archiveBackButton);
        if (back != null) {
            // Leaving commits any queued deletes: the owner swiped deliberately, so navigating
            // away confirms that intent rather than silently cancelling it.
            back.setOnClickListener(v -> {
                if (archiveCurrentThreadTitle != null) {
                    archiveCurrentThreadTitle = null;
                    archiveCurrentThreadPkg = null;
                    updateArchiveViewModeButtons(view);
                    if (archiveSearchRunnable != null) handler.removeCallbacks(archiveSearchRunnable);
                    renderArchive(view);
                    return;
                }
                if (archiveSearchRunnable != null) handler.removeCallbacks(archiveSearchRunnable);
                flushArchiveDeletes();
                showLogs();
            });
        }

        Button btnTimeline = view.findViewById(R.id.archiveModeTimeline);
        if (btnTimeline != null) {
            btnTimeline.setOnClickListener(v -> {
                if (archiveViewMode != ARCHIVE_VIEW_TIMELINE || archiveCurrentThreadTitle != null) {
                    archiveViewMode = ARCHIVE_VIEW_TIMELINE;
                    archiveCurrentThreadTitle = null;
                    archiveCurrentThreadPkg = null;
                    updateArchiveViewModeButtons(view);
                    if (archiveSearchRunnable != null) handler.removeCallbacks(archiveSearchRunnable);
                    renderArchive(view);
                }
            });
        }

        Button btnConvs = view.findViewById(R.id.archiveModeConversations);
        if (btnConvs != null) {
            btnConvs.setOnClickListener(v -> {
                if (archiveViewMode != ARCHIVE_VIEW_CONVERSATIONS || archiveCurrentThreadTitle != null) {
                    archiveViewMode = ARCHIVE_VIEW_CONVERSATIONS;
                    archiveCurrentThreadTitle = null;
                    archiveCurrentThreadPkg = null;
                    updateArchiveViewModeButtons(view);
                    if (archiveSearchRunnable != null) handler.removeCallbacks(archiveSearchRunnable);
                    renderArchive(view);
                }
            });
        }

        Button threadBack = view.findViewById(R.id.archiveThreadBackButton);
        if (threadBack != null) {
            threadBack.setOnClickListener(v -> {
                archiveCurrentThreadTitle = null;
                archiveCurrentThreadPkg = null;
                updateArchiveViewModeButtons(view);
                if (archiveSearchRunnable != null) handler.removeCallbacks(archiveSearchRunnable);
                renderArchive(view);
            });
        }

        View apps = view.findViewById(R.id.archiveAppsButton);
        if (apps != null) {
            apps.setOnClickListener(v -> ArchiveAppPicker.show(activity, () -> {
                if (archiveSearchRunnable != null) handler.removeCallbacks(archiveSearchRunnable);
                renderArchive(view);
            }));
        }

        View export = view.findViewById(R.id.archiveExportButton);
        if (export != null) export.setOnClickListener(v -> exportArchive(view));

        // Capture is push-based, so the list does not update itself while the screen sits open.
        // Switching tabs and returning worked only because that rebuilt the view; an explicit
        // rescan is the honest version of that.
        View refresh = view.findViewById(R.id.archiveRefreshButton);
        if (refresh != null) {
            refresh.setOnClickListener(v -> {
                if (archiveSearchRunnable != null) handler.removeCallbacks(archiveSearchRunnable);
                IrisNotificationListener.ensureConnected(activity);
                int offered = IrisNotificationListener.captureVisibleNow();
                renderArchive(view);
                if (offered > 0) {
                    toast("Rescanned. " + offered + " active notification(s) evaluated.");
                } else {
                    toast("Rescanned. No new notifications to capture.");
                }
            });
        }

        // Only route to notification access: there is no runtime permission dialog for it, so a
        // request dialog here would silently do nothing. Hidden unless access is actually missing.
        View grant = view.findViewById(R.id.archiveGrantButton);
        if (grant != null) {
            grant.setOnClickListener(v -> openNotificationAccessSettings());
        }

        // Diagnostic: dumps what the live notifications actually contain on THIS device, which is
        // the only reliable way to answer what can and cannot be captured. The report includes
        // message text, so it is shown and copyable but never written to LogStore.
        View probe = view.findViewById(R.id.archiveInspectButton);
        if (probe != null) {
            probe.setOnClickListener(v -> {
                IrisNotificationListener.ensureConnected(activity);
                String report = NotificationInspector.report(activity);
                new AlertDialog.Builder(activity)
                        .setTitle("What I can actually see")
                        .setMessage(report)
                        .setNeutralButton("Copy", (d, w) -> {
                            android.content.ClipboardManager clip =
                                    (android.content.ClipboardManager)
                                            getSystemService(Context.CLIPBOARD_SERVICE);
                            if (clip != null) {
                                clip.setPrimaryClip(android.content.ClipData.newPlainText(
                                        "IRIS notification probe", report));
                                toast("Copied. Send it to whoever is debugging me.");
                            }
                        })
                        .setPositiveButton("Fine", null)
                        .show();
            });
        }

        View deleteAll = view.findViewById(R.id.archiveDeleteAllButton);
        if (deleteAll != null) {
            deleteAll.setOnClickListener(v -> new AlertDialog.Builder(activity)
                    .setTitle("Burn it all down?")
                    .setMessage("This wipes every notification I ever saved for you, STG \u2014 "
                            + "including the ones somebody tried to take back. No undo. No backup. "
                            + "The archive is deliberately excluded from your phone backup, so "
                            + "once it's gone it is properly gone.\n\nExport first if you'd like "
                            + "to keep the evidence.")
                    .setNegativeButton("On reflection, no", null)
                    .setPositiveButton("Erase everything", (d, w) -> {
                        if (archiveSearchRunnable != null) handler.removeCallbacks(archiveSearchRunnable);

                        // Clear any pending swipe-delete operations immediately
                        for (Runnable commit : pendingDeleteCommits.values()) handler.removeCallbacks(commit);
                        pendingDeleteCommits.clear();
                        pendingDeletes.clear();
                        hideUndoBar(view);

                        // Reset thread view, expanded card states, filters, and pagination
                        archiveCurrentThreadTitle = null;
                        archiveCurrentThreadPkg = null;
                        archiveExpandedRowIds.clear();
                        archiveHasMore = false;
                        archiveQuickFilter = NotificationArchive.Filter.QUICK_ALL;
                        archiveDatePreset = DATE_PRESET_ALL;
                        setupArchiveFilterChips(view);
                        setupArchiveDateChips(view);
                        EditText searchBox = view.findViewById(R.id.archiveSearch);
                        if (searchBox != null) searchBox.setText("");

                        // Immediately clear the ListView adapter on the UI thread so old rows disappear instantly
                        ListView list = view.findViewById(R.id.archiveList);
                        if (list != null) {
                            ArchiveAdapter adapter = getArchiveAdapter(list);
                            if (adapter != null) adapter.setItems(java.util.Collections.emptyList());
                            ArchiveConversationAdapter convAdapter = getConversationAdapter(list);
                            if (convAdapter != null) convAdapter.setItems(java.util.Collections.emptyList());
                        }

                        // Immediately dismiss all clearable notifications from the Android notification shade
                        IrisNotificationListener.dismissAll();

                        // Set wipe barrier to ignore shade notifications that arrived before or during this wipe
                        long wipeEpoch = System.currentTimeMillis() + 1000L;
                        settings.setLastArchiveWipeAt(wipeEpoch);
                        settings.setArchiveRowsSinceExport(0);
                        IrisNotificationListener.setLastWipeEpoch(wipeEpoch);
                        IrisNotificationListener.clearTracked();

                        int removed = NotificationArchive.deleteEverything(activity);
                        ConversationReadTracker.clearAll(activity);
                        NotificationStore.clearAll(activity);
                        FlaggedStore.get(activity).clearAll();
                        archiveLastSignature = NotificationArchive.signature(activity);

                        toast(removed + " entries gone. I remember nothing. Allegedly.");
                        renderArchive(view);
                    }).show());
        }

        if (search != null) {
            search.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(android.text.Editable s) {
                    if (archiveSearchRunnable != null) handler.removeCallbacks(archiveSearchRunnable);
                    archiveSearchRunnable = () -> renderArchive(view);
                    handler.postDelayed(archiveSearchRunnable, ARCHIVE_SEARCH_DEBOUNCE_MS);
                }
            });
        }
        setupArchiveFilterChips(view);
        setupArchiveDateChips(view);
        ListView list = view.findViewById(R.id.archiveList);
        if (list != null) {
            archiveFooterView = createArchiveFooterView(view);
            list.addFooterView(archiveFooterView, null, true);
            list.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView v, int scrollState) { }

                @Override
                public void onScroll(AbsListView v, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                    if (archiveHasMore && !archiveIsLoadingMore && totalItemCount > 0) {
                        if (firstVisibleItem + visibleItemCount >= totalItemCount - 3) {
                            loadMoreArchiveRows(view);
                        }
                    }
                }
            });
        }

        Button btnDensity = view.findViewById(R.id.archiveDensityToggle);
        if (btnDensity != null) {
            btnDensity.setOnClickListener(v -> {
                archiveDensityMode = (archiveDensityMode == ARCHIVE_DENSITY_DETAILED)
                        ? ARCHIVE_DENSITY_COMPACT : ARCHIVE_DENSITY_DETAILED;
                updateArchiveDensityButton(view);
                renderArchive(view);
            });
        }

        updateArchiveViewModeButtons(view);
        updateArchiveDensityButton(view);
        startArchiveWatch(view);
        renderArchive(view);
    }

    private void updateArchiveDensityButton(View root) {
        if (root == null) return;
        Button btnDensity = root.findViewById(R.id.archiveDensityToggle);
        if (btnDensity != null) {
            boolean isCompact = (archiveDensityMode == ARCHIVE_DENSITY_COMPACT);
            btnDensity.setText(isCompact ? "DETAILED ▥" : "COMPACT ▤");
            btnDensity.setBackgroundResource(isCompact ? R.drawable.bg_cyber_button : R.drawable.bg_cyber_row);
            btnDensity.setTextColor(getColor(isCompact ? R.color.deck_accent : R.color.deck_text_dim));
            btnDensity.setTypeface(Typeface.MONOSPACE, isCompact ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void startArchiveWatch(final View view) {
        archiveLastSignature = NotificationArchive.signature(activity);
        view.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                if (!view.isAttachedToWindow() || activity == null || activity.getCommsSection() != 0) return;

                archiveQueryExecutor.execute(() -> {
                    final String current = NotificationArchive.signature(activity);
                    if (!current.equals(archiveLastSignature)) {
                        archiveLastSignature = current;
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            if (!view.isAttachedToWindow() || activity == null || activity.getCommsSection() != 0) return;
                            EditText search = view.findViewById(R.id.archiveSearch);
                            boolean isSearching = (search != null && search.getText().length() > 0 && archiveSearchRunnable != null);
                            if (!isSearching) {
                                renderArchive(view);
                            }
                        });
                    }
                });

                if (view.isAttachedToWindow() && activity != null && activity.getCommsSection() == 0) {
                    view.postDelayed(this, 2500);
                }
            }
        }, 2500);
    }

    private void updateArchiveViewModeButtons(View root) {
        if (root == null) return;
        Button btnTimeline = root.findViewById(R.id.archiveModeTimeline);
        Button btnConvs = root.findViewById(R.id.archiveModeConversations);
        LinearLayout threadHeader = root.findViewById(R.id.archiveThreadHeader);

        boolean isTimeline = archiveViewMode == ARCHIVE_VIEW_TIMELINE;
        if (btnTimeline != null) {
            btnTimeline.setBackgroundResource(isTimeline ? R.drawable.bg_cyber_button : R.drawable.bg_cyber_row);
            btnTimeline.setTextColor(getColor(isTimeline ? R.color.deck_accent : R.color.deck_text_dim));
            btnTimeline.setTypeface(Typeface.MONOSPACE, isTimeline ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (btnConvs != null) {
            btnConvs.setBackgroundResource(!isTimeline ? R.drawable.bg_cyber_button : R.drawable.bg_cyber_row);
            btnConvs.setTextColor(getColor(!isTimeline ? R.color.deck_accent : R.color.deck_text_dim));
            btnConvs.setTypeface(Typeface.MONOSPACE, !isTimeline ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (threadHeader != null) {
            threadHeader.setVisibility(archiveCurrentThreadTitle != null ? View.VISIBLE : View.GONE);
        }
    }

    private void openConversationThread(ConversationGroup conv, View root) {
        if (conv == null || root == null) return;
        archiveCurrentThreadTitle = conv.threadTitle;
        archiveCurrentThreadPkg = conv.pkg;
        updateArchiveViewModeButtons(root);
        TextView titleTv = root.findViewById(R.id.archiveThreadTitle);
        if (titleTv != null) {
            String contact = ContactResolver.display(activity, conv.displayName());
            titleTv.setText(contact + " \u00b7 [" + conv.displayApp() + "] (" + conv.totalCount + " msgs"
                    + (conv.flaggedCount > 0 ? " \u00b7 🚩 " + conv.flaggedCount : "") + ")");
        }
        if (archiveSearchRunnable != null) handler.removeCallbacks(archiveSearchRunnable);
        renderArchive(root);
    }

    private void setupArchiveFilterChips(final View view) {
        LinearLayout chips = view.findViewById(R.id.archiveFilterChips);
        if (chips == null) return;
        chips.removeAllViews();
        addArchiveFilterChip(chips, "ALL", NotificationArchive.Filter.QUICK_ALL, view);
        addArchiveFilterChip(chips, "FLAGGED 🚩", NotificationArchive.Filter.QUICK_FLAGGED, view);
        addArchiveFilterChip(chips, "PHOTOS 📷", NotificationArchive.Filter.QUICK_PHOTOS, view);
        addArchiveFilterChip(chips, "VIDEOS 🎬", NotificationArchive.Filter.QUICK_VIDEOS, view);
        addArchiveFilterChip(chips, "AUDIO 🎙️", NotificationArchive.Filter.QUICK_AUDIO, view);
        addArchiveFilterChip(chips, "DOCS 📄", NotificationArchive.Filter.QUICK_DOCS, view);
        addArchiveFilterChip(chips, "WHATSAPP", NotificationArchive.Filter.QUICK_WHATSAPP, view);
        addArchiveFilterChip(chips, "SMS", NotificationArchive.Filter.QUICK_SMS, view);
    }

    private void addArchiveFilterChip(LinearLayout parent, String label, final int filterIndex, final View rootView) {
        TextView chip = new TextView(activity);
        chip.setText(label);
        chip.setTextSize(10f);
        chip.setTypeface(android.graphics.Typeface.MONOSPACE,
                archiveQuickFilter == filterIndex ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        chip.setLayoutParams(lp);

        boolean active = archiveQuickFilter == filterIndex;
        chip.setTextColor(getColor(active ? R.color.text_primary : R.color.deck_text_dim));
        chip.setBackgroundResource(active ? R.drawable.bg_cyber_button : R.drawable.bg_cyber_row);

        chip.setOnClickListener(v -> {
            if (archiveQuickFilter == filterIndex) return;
            archiveQuickFilter = filterIndex;
            setupArchiveFilterChips(rootView);
            if (archiveSearchRunnable != null) handler.removeCallbacks(archiveSearchRunnable);
            renderArchive(rootView);
        });
        parent.addView(chip);
    }

    private void setupArchiveDateChips(final View view) {
        LinearLayout chips = view.findViewById(R.id.archiveDateChips);
        if (chips == null) return;
        chips.removeAllViews();
        addArchiveDateChip(chips, "ALL TIME", DATE_PRESET_ALL, view);
        addArchiveDateChip(chips, "TODAY", DATE_PRESET_TODAY, view);
        addArchiveDateChip(chips, "YESTERDAY", DATE_PRESET_YESTERDAY, view);
        addArchiveDateChip(chips, "THIS WEEK", DATE_PRESET_THIS_WEEK, view);

        String customLabel = "CUSTOM 📅";
        if (archiveDatePreset == DATE_PRESET_CUSTOM && archiveCustomFromMs > 0 && archiveCustomToMs > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM d", Locale.US);
            customLabel = sdf.format(new Date(archiveCustomFromMs)) + " \u2013 "
                    + sdf.format(new Date(archiveCustomToMs)) + " 📅";
        }
        addArchiveDateChip(chips, customLabel, DATE_PRESET_CUSTOM, view);
    }

    private void addArchiveDateChip(LinearLayout parent, String label, final int presetIndex, final View rootView) {
        TextView chip = new TextView(activity);
        chip.setText(label);
        chip.setTextSize(10f);
        chip.setTypeface(android.graphics.Typeface.MONOSPACE,
                archiveDatePreset == presetIndex ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        chip.setLayoutParams(lp);

        boolean active = archiveDatePreset == presetIndex;
        chip.setTextColor(getColor(active ? R.color.text_primary : R.color.deck_text_dim));
        chip.setBackgroundResource(active ? R.drawable.bg_cyber_button : R.drawable.bg_cyber_row);

        chip.setOnClickListener(v -> {
            if (presetIndex == DATE_PRESET_CUSTOM) {
                showCustomDatePickerDialog(rootView);
                return;
            }
            if (archiveDatePreset == presetIndex) return;
            archiveDatePreset = presetIndex;
            setupArchiveDateChips(rootView);
            if (archiveSearchRunnable != null) handler.removeCallbacks(archiveSearchRunnable);
            renderArchive(rootView);
        });
        parent.addView(chip);
    }

    private void showCustomDatePickerDialog(final View rootView) {
        if (archiveDatePreset == DATE_PRESET_CUSTOM && archiveCustomFromMs > 0) {
            new AlertDialog.Builder(activity)
                    .setTitle("Date Range Filter")
                    .setItems(new String[]{"\uD83D\uDCC5 Change date range", "\u21BA Reset to ALL TIME"}, (d, which) -> {
                        if (which == 0) {
                            promptStartDatePicker(rootView);
                        } else {
                            archiveDatePreset = DATE_PRESET_ALL;
                            archiveCustomFromMs = 0L;
                            archiveCustomToMs = 0L;
                            setupArchiveDateChips(rootView);
                            if (archiveSearchRunnable != null) handler.removeCallbacks(archiveSearchRunnable);
                            renderArchive(rootView);
                        }
                    })
                    .show();
            return;
        }
        promptStartDatePicker(rootView);
    }

    private void promptStartDatePicker(final View rootView) {
        final Calendar cal = Calendar.getInstance();
        if (archiveCustomFromMs > 0) cal.setTimeInMillis(archiveCustomFromMs);

        toast("Select START date for vault filter");
        DatePickerDialog startDialog = new DatePickerDialog(activity, (view, year, month, dayOfMonth) -> {
            final Calendar startCal = Calendar.getInstance();
            startCal.set(Calendar.YEAR, year);
            startCal.set(Calendar.MONTH, month);
            startCal.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            startCal.set(Calendar.HOUR_OF_DAY, 0);
            startCal.set(Calendar.MINUTE, 0);
            startCal.set(Calendar.SECOND, 0);
            startCal.set(Calendar.MILLISECOND, 0);

            final Calendar endDefault = Calendar.getInstance();
            if (archiveCustomToMs > 0 && archiveCustomToMs >= startCal.getTimeInMillis()) {
                endDefault.setTimeInMillis(archiveCustomToMs);
            } else {
                endDefault.setTimeInMillis(startCal.getTimeInMillis());
            }

            toast("Select END date for vault filter");
            DatePickerDialog endDialog = new DatePickerDialog(activity, (endView, endYear, endMonth, endDayOfMonth) -> {
                Calendar endCal = Calendar.getInstance();
                endCal.set(Calendar.YEAR, endYear);
                endCal.set(Calendar.MONTH, endMonth);
                endCal.set(Calendar.DAY_OF_MONTH, endDayOfMonth);
                endCal.set(Calendar.HOUR_OF_DAY, 23);
                endCal.set(Calendar.MINUTE, 59);
                endCal.set(Calendar.SECOND, 59);
                endCal.set(Calendar.MILLISECOND, 999);

                long startMs = startCal.getTimeInMillis();
                long endMs = endCal.getTimeInMillis();
                if (startMs > endMs) {
                    long tmp = startMs;
                    startMs = endMs;
                    endMs = tmp;
                }

                archiveCustomFromMs = startMs;
                archiveCustomToMs = endMs;
                archiveDatePreset = DATE_PRESET_CUSTOM;
                setupArchiveDateChips(rootView);
                if (archiveSearchRunnable != null) handler.removeCallbacks(archiveSearchRunnable);
                renderArchive(rootView);
            }, endDefault.get(Calendar.YEAR), endDefault.get(Calendar.MONTH), endDefault.get(Calendar.DAY_OF_MONTH));

            endDialog.setTitle("Select END Date");
            endDialog.show();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));

        startDialog.setTitle("Select START Date");
        startDialog.show();
    }

    private long[] getArchiveDateRange() {
        if (archiveDatePreset == DATE_PRESET_ALL) {
            return new long[]{0L, 0L};
        }
        if (archiveDatePreset == DATE_PRESET_TODAY) {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            long from = c.getTimeInMillis();
            c.set(Calendar.HOUR_OF_DAY, 23);
            c.set(Calendar.MINUTE, 59);
            c.set(Calendar.SECOND, 59);
            c.set(Calendar.MILLISECOND, 999);
            long to = c.getTimeInMillis();
            return new long[]{from, to};
        }
        if (archiveDatePreset == DATE_PRESET_YESTERDAY) {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_YEAR, -1);
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            long from = c.getTimeInMillis();
            c.set(Calendar.HOUR_OF_DAY, 23);
            c.set(Calendar.MINUTE, 59);
            c.set(Calendar.SECOND, 59);
            c.set(Calendar.MILLISECOND, 999);
            long to = c.getTimeInMillis();
            return new long[]{from, to};
        }
        if (archiveDatePreset == DATE_PRESET_THIS_WEEK) {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.DAY_OF_WEEK, c.getFirstDayOfWeek());
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            long from = c.getTimeInMillis();
            if (from > System.currentTimeMillis()) {
                c.add(Calendar.WEEK_OF_YEAR, -1);
                from = c.getTimeInMillis();
            }
            long to = System.currentTimeMillis();
            return new long[]{from, to};
        }
        if (archiveDatePreset == DATE_PRESET_CUSTOM) {
            return new long[]{archiveCustomFromMs, archiveCustomToMs};
        }
        return new long[]{0L, 0L};
    }

    private String getArchiveDateRangeLabel() {
        if (archiveDatePreset == DATE_PRESET_TODAY) return "TODAY";
        if (archiveDatePreset == DATE_PRESET_YESTERDAY) return "YESTERDAY";
        if (archiveDatePreset == DATE_PRESET_THIS_WEEK) return "THIS WEEK";
        if (archiveDatePreset == DATE_PRESET_CUSTOM && archiveCustomFromMs > 0 && archiveCustomToMs > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM d", Locale.US);
            return sdf.format(new Date(archiveCustomFromMs)) + " \u2013 " + sdf.format(new Date(archiveCustomToMs));
        }
        return "ALL TIME";
    }

    private TextView createCyberActionButton(String text) {
        TextView btn = new TextView(activity);
        btn.setText(text);
        btn.setTextSize(10f);
        btn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btn.setTextColor(getColor(R.color.deck_accent));
        btn.setGravity(Gravity.CENTER);
        btn.setBackgroundResource(R.drawable.bg_cyber_button);
        btn.setPadding(dp(6), dp(5), dp(6), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void shareArchiveReceipt(final ArchiveRow row) {
        if (row == null) return;
        String who = row.sender.isEmpty() ? row.rawTitle : row.sender;
        who = ContactResolver.display(activity, who);
        String where = row.accountLabel.isEmpty() ? row.appLabel : row.accountLabel;
        String time = DeletedMessageRules.formatTime(row.postTime, java.util.TimeZone.getDefault());
        String text = "[IRIS RECEIPT]\nFrom: " + who + " (" + where + ")\nTime: " + time + "\n\n" + row.body;

        Intent share = new Intent(Intent.ACTION_SEND);
        if (row.hasImage()) {
            try {
                Uri contentUri = IrisFileProvider.uriFor(activity, row.imagePath);
                String mime = ArchiveImagePolicy.mimeTypeFor(row.imagePath);
                share.putExtra(Intent.EXTRA_STREAM, contentUri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                share.setType(mime != null ? mime : "*/*");
            } catch (Throwable t) {
                share.setType("text/plain");
            }
        } else {
            share.setType("text/plain");
        }
        share.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(share, "Share receipt via\u2026"));
        } catch (Exception e) {
            toast("No app available to share this receipt.");
        }
    }

    private static class ZoomableTouchListener implements View.OnTouchListener {
        private final ScaleGestureDetector scaleDetector;
        private float scaleFactor = 1.0f;
        private float lastX, lastY;
        private float startX, startY;
        private final Runnable onSwipePrev;
        private final Runnable onSwipeNext;

        ZoomableTouchListener(Context context, Runnable onSwipePrev, Runnable onSwipeNext) {
            this.onSwipePrev = onSwipePrev;
            this.onSwipeNext = onSwipeNext;
            this.scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    scaleFactor *= detector.getScaleFactor();
                    scaleFactor = Math.max(1.0f, Math.min(scaleFactor, 4.0f));
                    return true;
                }
            });
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            ImageView img = (ImageView) v;

            int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX();
                    lastY = event.getY();
                    startX = event.getX();
                    startY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!scaleDetector.isInProgress()) {
                        float dx = event.getX() - lastX;
                        float dy = event.getY() - lastY;
                        if (scaleFactor > 1.05f) {
                            img.setTranslationX(img.getTranslationX() + dx);
                            img.setTranslationY(img.getTranslationY() + dy);
                        }
                        lastX = event.getX();
                        lastY = event.getY();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (scaleFactor <= 1.05f) {
                        float totalDx = event.getX() - startX;
                        float totalDy = Math.abs(event.getY() - startY);
                        if (totalDx > 100 && totalDy < 150 && onSwipePrev != null) {
                            onSwipePrev.run();
                            return true;
                        } else if (totalDx < -100 && totalDy < 150 && onSwipeNext != null) {
                            onSwipeNext.run();
                            return true;
                        }
                    }
                    break;
            }

            img.setScaleX(scaleFactor);
            img.setScaleY(scaleFactor);
            if (scaleFactor <= 1.0f) {
                img.setTranslationX(0f);
                img.setTranslationY(0f);
            }
            return true;
        }
    }

    private void showArchiveMediaLightbox(final ArchiveRow initialRow) {
        if (initialRow == null) return;
        if (initialRow.isDocument()) {
            openArchiveDocument(initialRow);
            return;
        }

        ListView list = findViewById(R.id.archiveList);
        ArchiveAdapter adapter = getArchiveAdapter(list);
        final java.util.List<ArchiveRow> mediaRows = new java.util.ArrayList<>();
        int initIdx = 0;
        if (adapter != null) {
            for (ArchiveRow r : adapter.getRawRows()) {
                if (r.hasImage() && !r.isDocument() && !r.isAudio()) {
                    if (r.id == initialRow.id) {
                        initIdx = mediaRows.size();
                    }
                    mediaRows.add(r);
                }
            }
        }
        if (mediaRows.isEmpty()) {
            mediaRows.add(initialRow);
        }
        final int[] currentIndex = new int[] { initIdx };

        final Dialog lightbox = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final RelativeLayout root = new RelativeLayout(activity);
        root.setBackgroundColor(0xFF070816);

        // Top Header Bar
        final LinearLayout topBar = new LinearLayout(activity);
        topBar.setId(View.generateViewId());
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(16), dp(16), dp(12));
        topBar.setBackgroundColor(0xCC0F1028);

        TextView closeBtn = new TextView(activity);
        closeBtn.setText("✕");
        closeBtn.setTextSize(18f);
        closeBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        closeBtn.setTextColor(getColor(R.color.deck_accent));
        closeBtn.setPadding(dp(4), dp(4), dp(12), dp(4));
        closeBtn.setOnClickListener(v -> lightbox.dismiss());
        topBar.addView(closeBtn);

        LinearLayout titleCol = new LinearLayout(activity);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        titleCol.setLayoutParams(tlp);

        final TextView titleTv = new TextView(activity);
        titleTv.setTextSize(13f);
        titleTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        titleTv.setTextColor(getColor(R.color.deck_accent));
        titleTv.setSingleLine(true);
        titleCol.addView(titleTv);

        final TextView subtitleTv = new TextView(activity);
        subtitleTv.setTextSize(10f);
        subtitleTv.setTypeface(Typeface.MONOSPACE);
        subtitleTv.setTextColor(getColor(R.color.deck_text_dim));
        titleCol.addView(subtitleTv);

        topBar.addView(titleCol);

        TextView shareBtn = new TextView(activity);
        shareBtn.setText("↗");
        shareBtn.setTextSize(18f);
        shareBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        shareBtn.setTextColor(getColor(R.color.deck_accent));
        shareBtn.setPadding(dp(10), dp(4), dp(4), dp(4));
        topBar.addView(shareBtn);

        RelativeLayout.LayoutParams topLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        topLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        root.addView(topBar, topLp);

        // Bottom Action Bar
        final LinearLayout bottomBar = new LinearLayout(activity);
        bottomBar.setId(View.generateViewId());
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(16), dp(10), dp(16), dp(16));
        bottomBar.setBackgroundColor(0xCC0F1028);

        final TextView captionTv = new TextView(activity);
        captionTv.setTextSize(12f);
        captionTv.setTextColor(getColor(R.color.deck_text));
        captionTv.setMaxLines(3);
        captionTv.setEllipsize(TextUtils.TruncateAt.END);
        captionTv.setPadding(0, 0, 0, dp(8));
        bottomBar.addView(captionTv);

        LinearLayout bottomNavRow = new LinearLayout(activity);
        bottomNavRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomNavRow.setGravity(Gravity.CENTER_VERTICAL);

        final TextView prevBtn = createCyberActionButton("◀ PREV");
        final TextView saveBtn = createCyberActionButton("💾 SAVE");
        final TextView copyBtn = createCyberActionButton("📋 COPY");
        final TextView nextBtn = createCyberActionButton("NEXT ▶");

        bottomNavRow.addView(prevBtn);
        bottomNavRow.addView(saveBtn);
        bottomNavRow.addView(copyBtn);
        bottomNavRow.addView(nextBtn);
        bottomBar.addView(bottomNavRow);

        RelativeLayout.LayoutParams btmLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        btmLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        root.addView(bottomBar, btmLp);

        // Center Content Container
        final FrameLayout centerContainer = new FrameLayout(activity);
        RelativeLayout.LayoutParams centerLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT);
        centerLp.addRule(RelativeLayout.BELOW, topBar.getId());
        centerLp.addRule(RelativeLayout.ABOVE, bottomBar.getId());
        root.addView(centerContainer, centerLp);

        final Runnable[] updateMediaDisplay = new Runnable[1];
        updateMediaDisplay[0] = () -> {
            centerContainer.removeAllViews();
            int idx = currentIndex[0];
            if (idx < 0 || idx >= mediaRows.size()) return;
            final ArchiveRow currentRow = mediaRows.get(idx);

            String who = currentRow.sender.isEmpty() ? currentRow.rawTitle : currentRow.sender;
            who = ContactResolver.display(activity, who);
            String where = currentRow.accountLabel.isEmpty() ? currentRow.appLabel : currentRow.accountLabel;
            String time = DeletedMessageRules.formatTime(currentRow.postTime, java.util.TimeZone.getDefault());

            titleTv.setText(who);
            subtitleTv.setText(where + " \u00b7 " + time + " \u00b7 [" + (idx + 1) + " / " + mediaRows.size() + "]");
            captionTv.setText(currentRow.body.isEmpty() ? "(no caption)" : currentRow.body);

            prevBtn.setEnabled(idx > 0);
            prevBtn.setAlpha(idx > 0 ? 1f : 0.4f);
            nextBtn.setEnabled(idx < mediaRows.size() - 1);
            nextBtn.setAlpha(idx < mediaRows.size() - 1 ? 1f : 0.4f);

            shareBtn.setOnClickListener(v -> shareArchiveReceipt(currentRow));
            saveBtn.setOnClickListener(v -> {
                pendingImageRow = currentRow;
                try {
                    Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(currentRow.isVideo() ? "video/mp4" : "image/jpeg")
                            .putExtra(Intent.EXTRA_TITLE, "iris-" + currentRow.imagePath);
                    startActivityForResult(save, SAVE_ARCHIVE_IMAGE);
                } catch (Exception e) {
                    toast("No app on this phone can save a file.");
                }
            });
            copyBtn.setOnClickListener(v -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("IRIS Media Text", currentRow.body));
                    toast("Caption copied to clipboard");
                }
            });

            if (currentRow.isVideo()) {
                java.io.File file = ArchiveImages.fileFor(activity, currentRow.imagePath);
                if (file != null && file.exists() && file.length() > 0) {
                    android.widget.VideoView videoView = new android.widget.VideoView(activity);
                    FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER);
                    videoView.setLayoutParams(vlp);
                    centerContainer.addView(videoView);

                    android.widget.MediaController controller = new android.widget.MediaController(activity);
                    controller.setAnchorView(videoView);
                    videoView.setMediaController(controller);
                    videoView.setVideoPath(file.getAbsolutePath());
                    videoView.setOnPreparedListener(mp -> {
                        mp.setLooping(true);
                        videoView.start();
                    });
                } else {
                    TextView gone = new TextView(activity);
                    gone.setText("[ Video file is missing ]");
                    gone.setTypeface(Typeface.MONOSPACE);
                    gone.setTextColor(getColor(R.color.deck_text_dim));
                    gone.setGravity(Gravity.CENTER);
                    centerContainer.addView(gone);
                }
            } else {
                final ImageView photoView = new ImageView(activity);
                FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER);
                photoView.setLayoutParams(plp);
                photoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                centerContainer.addView(photoView);

                Bitmap cached = ArchiveImages.cachedThumbnail(currentRow.imagePath);
                if (cached != null) {
                    photoView.setImageBitmap(cached);
                }
                ArchiveImages.loadFullAsync(activity, currentRow.imagePath, full -> {
                    if (full != null) {
                        photoView.setImageBitmap(full);
                    }
                });

                Runnable onSwipePrev = () -> {
                    if (currentIndex[0] > 0) {
                        currentIndex[0]--;
                        updateMediaDisplay[0].run();
                    }
                };
                Runnable onSwipeNext = () -> {
                    if (currentIndex[0] < mediaRows.size() - 1) {
                        currentIndex[0]++;
                        updateMediaDisplay[0].run();
                    }
                };
                photoView.setOnTouchListener(new ZoomableTouchListener(activity, onSwipePrev, onSwipeNext));
            }
        };

        prevBtn.setOnClickListener(v -> {
            if (currentIndex[0] > 0) {
                currentIndex[0]--;
                updateMediaDisplay[0].run();
            }
        });
        nextBtn.setOnClickListener(v -> {
            if (currentIndex[0] < mediaRows.size() - 1) {
                currentIndex[0]++;
                updateMediaDisplay[0].run();
            }
        });

        updateMediaDisplay[0].run();
        lightbox.setContentView(root);
        lightbox.show();
    }

    private void showArchiveImage(final ArchiveRow row) {
        showArchiveMediaLightbox(row);
    }

    private void showArchiveVideo(final ArchiveRow row) {
        showArchiveMediaLightbox(row);
    }

    /** The row whose image is being saved, held across the file-picker round trip. */
    private ArchiveRow pendingImageRow;

    /** Write the chosen image to the location the owner picked. */
    private void writeArchiveImage(Uri destination) {
        ArchiveRow row = pendingImageRow;
        pendingImageRow = null;
        if (row == null || destination == null) return;
        try (java.io.OutputStream out = getContentResolver().openOutputStream(destination)) {
            boolean ok = ArchiveImages.copyTo(activity, row.imagePath, out);
            toast(ok ? "Saved. Do what you like with it."
                     : "Could not save that one, which is embarrassing for both of us.");
        } catch (Exception e) {
            toast("Could not save that one, which is embarrassing for both of us.");
        }
    }

    private MediaPlayer archivePlayer;
    private String currentlyPlayingAudioPath;
    private ArchiveViewHolder currentlyPlayingAudioHolder;
    private long currentlyPlayingTotalMs;
    private final Runnable audioProgressUpdater = new Runnable() {
        @Override
        public void run() {
            if (archivePlayer != null && archivePlayer.isPlaying() && currentlyPlayingAudioHolder != null) {
                int current = archivePlayer.getCurrentPosition();
                int total = archivePlayer.getDuration();
                if (total > 0) {
                    float frac = (float) current / (float) total;
                    currentlyPlayingAudioHolder.audioWaveform.setProgress(frac);
                    currentlyPlayingAudioHolder.audioTime.setText(
                            ArchiveImages.formatDuration(current) + " / " + ArchiveImages.formatDuration(total));
                }
                handler.postDelayed(this, 100);
            }
        }
    };

    /**
     * Toggle playback for a voice note directly within its archive card.
     */
    private void toggleArchiveAudioPlayback(final ArchiveRow row, final ArchiveViewHolder holder) {
        if (row.imagePath.equals(currentlyPlayingAudioPath) && archivePlayer != null) {
            if (archivePlayer.isPlaying()) {
                archivePlayer.pause();
                holder.audioPlayBtn.setText("\u25B6");
                handler.removeCallbacks(audioProgressUpdater);
            } else {
                archivePlayer.start();
                holder.audioPlayBtn.setText("\u23F8");
                currentlyPlayingAudioHolder = holder;
                handler.post(audioProgressUpdater);
            }
            return;
        }

        stopArchiveAudioPlayback();

        java.io.File file = ArchiveImages.fileFor(activity, row.imagePath);
        if (file == null || !file.exists() || file.length() == 0) {
            toast("That voice note is gone. I had it, and now I don't.");
            return;
        }

        try {
            archivePlayer = new MediaPlayer();
            archivePlayer.setDataSource(file.getAbsolutePath());
            archivePlayer.prepare();
            archivePlayer.start();

            currentlyPlayingAudioPath = row.imagePath;
            currentlyPlayingAudioHolder = holder;
            currentlyPlayingTotalMs = archivePlayer.getDuration();

            holder.audioPlayBtn.setText("\u23F8");
            handler.post(audioProgressUpdater);

            archivePlayer.setOnCompletionListener(mp -> {
                holder.audioPlayBtn.setText("\u25B6");
                holder.audioWaveform.setProgress(0f);
                holder.audioTime.setText(ArchiveImages.formatDuration(currentlyPlayingTotalMs));
                stopArchiveAudioPlayback();
            });

            archivePlayer.setOnErrorListener((mp, what, extra) -> {
                toast("Could not play voice note.");
                stopArchiveAudioPlayback();
                return true;
            });
        } catch (Throwable t) {
            toast("Could not play voice note.");
            stopArchiveAudioPlayback();
        }
    }

    /**
     * Stop any currently running voice note playback.
     */
    private void stopArchiveAudioPlayback() {
        handler.removeCallbacks(audioProgressUpdater);
        if (currentlyPlayingAudioHolder != null) {
            currentlyPlayingAudioHolder.audioPlayBtn.setText("\u25B6");
            currentlyPlayingAudioHolder = null;
        }
        currentlyPlayingAudioPath = null;
        if (archivePlayer != null) {
            try {
                if (archivePlayer.isPlaying()) archivePlayer.stop();
                archivePlayer.release();
            } catch (Throwable ignored) {
            } finally {
                archivePlayer = null;
            }
        }
    }

    /**
     * Save a copy of an archived voice note to external storage.
     */
    private void saveArchiveAudio(final ArchiveRow row) {
        pendingImageRow = row;
        try {
            String lower = row.imagePath.toLowerCase(java.util.Locale.US);
            String mime = "audio/ogg";
            if (lower.endsWith(".mp4") || lower.endsWith(".m4a")) mime = "audio/mp4";
            else if (lower.endsWith(".aac")) mime = "audio/aac";
            else if (lower.endsWith(".amr")) mime = "audio/amr";
            else if (lower.endsWith(".mp3")) mime = "audio/mpeg";

            Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_TITLE, "iris-" + row.imagePath);
            startActivityForResult(save, SAVE_ARCHIVE_IMAGE);
        } catch (Exception e) {
            toast("No app on this phone can save a file.");
        }
    }

    /**
     * Open an archived document with an external app using a grantable content:// URI.
     */
    private void openArchiveDocument(final ArchiveRow row) {
        java.io.File file = ArchiveImages.fileFor(activity, row.imagePath);
        if (file == null || !file.exists() || file.length() == 0) {
            toast("That document is gone. I had it, and now I don't.");
            return;
        }
        try {
            Uri contentUri = IrisFileProvider.uriFor(activity, row.imagePath);
            String mime = ArchiveImagePolicy.mimeTypeFor(row.imagePath);
            Intent open = new Intent(Intent.ACTION_VIEW);
            open.setDataAndType(contentUri, mime);
            open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent chooser = Intent.createChooser(open, "Open " + row.documentBadge() + " with\u2026");
            startActivity(chooser);
        } catch (android.content.ActivityNotFoundException e) {
            toast("No app installed to open " + row.documentBadge() + " files.");
        } catch (Exception e) {
            toast("Could not open this document.");
        }
    }

    /**
     * Save a copy of an archived document to external storage.
     */
    private void saveArchiveDocument(final ArchiveRow row) {
        pendingImageRow = row;
        try {
            String mime = ArchiveImagePolicy.mimeTypeFor(row.imagePath);
            Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_TITLE, "iris-" + row.imagePath);
            startActivityForResult(save, SAVE_ARCHIVE_IMAGE);
        } catch (Exception e) {
            toast("No app on this phone can save a file.");
        }
    }

    /**
     * Compact cyber waveform visualizer with playback progress track and touch scrubbing.
     */
    public static class WaveformView extends View {
        private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint inactivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress;
        private int seed = 42;
        private OnSeekListener seekListener;

        public interface OnSeekListener {
            void onSeek(float fraction);
        }

        public WaveformView(Context context) {
            super(context);
            init(context);
        }

        private void init(Context context) {
            activePaint.setColor(context.getColor(R.color.deck_accent));
            activePaint.setStyle(Paint.Style.FILL);

            inactivePaint.setColor(context.getColor(R.color.deck_text_dim));
            inactivePaint.setStyle(Paint.Style.FILL);
        }

        public void setSeed(int seed) {
            this.seed = seed;
            invalidate();
        }

        public void setProgress(float progress) {
            this.progress = Math.max(0f, Math.min(1f, progress));
            invalidate();
        }

        public void setOnSeekListener(OnSeekListener listener) {
            this.seekListener = listener;
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN
                    || event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                float x = Math.max(0, Math.min(event.getX(), getWidth()));
                float fraction = getWidth() > 0 ? x / (float) getWidth() : 0f;
                setProgress(fraction);
                if (seekListener != null) {
                    seekListener.onSeek(fraction);
                }
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            int barCount = 28;
            float totalGap = w * 0.35f;
            float gap = totalGap / (barCount - 1);
            float barWidth = (w - totalGap) / barCount;
            float minBarH = h * 0.18f;
            float maxBarH = h * 0.88f;

            java.util.Random rnd = new java.util.Random(seed);

            for (int i = 0; i < barCount; i++) {
                float normH = 0.25f + rnd.nextFloat() * 0.75f;
                float wave = (float) Math.sin((i / (double) barCount) * Math.PI);
                float barH = minBarH + (maxBarH - minBarH) * (normH * 0.7f + wave * 0.3f);

                float left = i * (barWidth + gap);
                float top = (h - barH) / 2f;
                float right = left + barWidth;
                float bottom = top + barH;
                float corner = barWidth / 2f;

                float barFrac = (float) i / (float) (barCount - 1);
                Paint paint = barFrac <= progress ? activePaint : inactivePaint;

                canvas.drawRoundRect(left, top, right, bottom, corner, corner, paint);
            }
        }
    }

    /**
     * Attach swipe-to-delete to one archive row.
     *
     * Replaces long-press as the primary delete route, which the owner found a chore. Implemented
     * directly rather than with ItemTouchHelper because the list is a LinearLayout in a ScrollView
     * and a RecyclerView would mean a new dependency; the thresholds live in SwipeDecision and are
     * covered offline.
     *
     * The delete is NOT immediate. The row leaves the list at once so the gesture feels decisive,
     * but the database write waits behind an undo window, because a deleted message cannot be
     * recovered from anywhere and an accidental flick would otherwise destroy the one copy.
     */
    private void attachSwipeToDelete(final View card, final ArchiveRow row, final View root) {
        final float[] start = new float[2];
        final long[] startedAt = new long[1];
        final boolean[] claimed = new boolean[1];
        final Runnable[] longPressCallback = new Runnable[1];

        card.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    start[0] = event.getRawX();
                    start[1] = event.getRawY();
                    startedAt[0] = System.currentTimeMillis();
                    claimed[0] = false;
                    longPressCallback[0] = () -> {
                        if (!claimed[0]) {
                            v.performLongClick();
                        }
                    };
                    handler.postDelayed(longPressCallback[0], android.view.ViewConfiguration.getLongPressTimeout());
                    return true;

                case android.view.MotionEvent.ACTION_MOVE: {
                    int touchSlop = android.view.ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
                    float dx = event.getRawX() - start[0];
                    float dy = event.getRawY() - start[1];
                    float absDx = Math.abs(dx);
                    float absDy = Math.abs(dy);

                    if (absDx > touchSlop || absDy > touchSlop) {
                        if (longPressCallback[0] != null) {
                            handler.removeCallbacks(longPressCallback[0]);
                            longPressCallback[0] = null;
                        }
                    }

                    // Deliberate horizontal swipe intent:
                    // Must exceed twice the touch slop AND be at least 2.5x greater than vertical movement
                    if (!claimed[0] && absDx > (touchSlop * 2) && absDx > (absDy * 2.5f)) {
                        claimed[0] = true;
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    }

                    if (claimed[0]) {
                        v.setTranslationX(dx);
                        v.setAlpha(SwipeDecision.dragAlpha(dx, v.getWidth()));
                        return true;
                    }

                    // Predominantly vertical drag: cancel card translation, release parent intercept, and yield to ListView
                    if (absDy > touchSlop && absDy >= absDx) {
                        if (v.getTranslationX() != 0) {
                            v.setTranslationX(0);
                            v.setAlpha(1f);
                        }
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                        }
                    }
                    return false;
                }

                case android.view.MotionEvent.ACTION_UP: {
                    if (longPressCallback[0] != null) {
                        handler.removeCallbacks(longPressCallback[0]);
                        longPressCallback[0] = null;
                    }
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    int touchSlop = android.view.ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
                    float dx = event.getRawX() - start[0];
                    float dy = event.getRawY() - start[1];
                    long elapsed = Math.max(1, System.currentTimeMillis() - startedAt[0]);
                    float velocity = dx / elapsed * 1000f;

                    if (!claimed[0]) {
                        v.setTranslationX(0);
                        v.setAlpha(1f);
                        if (Math.abs(dx) < touchSlop && Math.abs(dy) < touchSlop) {
                            if (row.hasImage() && !row.isAudio() && !row.isDocument()) {
                                showArchiveImage(row);
                            } else if (archiveDensityMode == ARCHIVE_DENSITY_COMPACT) {
                                v.performClick();
                            }
                        }
                        return false;
                    }

                    if (SwipeDecision.dismisses(dx, velocity, v.getWidth())) {
                        swipeAwayAndQueueDelete(v, row, root, dx >= 0);
                    } else {
                        v.animate().translationX(0).alpha(1f).setDuration(140).start();
                    }
                    return true;
                }

                case android.view.MotionEvent.ACTION_CANCEL:
                    if (longPressCallback[0] != null) {
                        handler.removeCallbacks(longPressCallback[0]);
                        longPressCallback[0] = null;
                    }
                    v.setTranslationX(0);
                    v.setAlpha(1f);
                    claimed[0] = false;
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return false;

                default:
                    return false;
            }
        });
    }

    /** Slide the row out, drop it from the list, and start the undo window. */
    private void swipeAwayAndQueueDelete(final View card, final ArchiveRow row,
                                         final View root, boolean toTheRight) {
        if (row.imagePath != null && row.imagePath.equals(currentlyPlayingAudioPath)) {
            stopArchiveAudioPlayback();
        }
        int distance = Math.max(card.getWidth(), dp(320));
        card.animate()
                .translationX(toTheRight ? distance : -distance)
                .alpha(0f)
                .setDuration(160)
                .withEndAction(() -> {
                    card.setTranslationX(0);
                    card.setAlpha(1f);
                    ListView list = root.findViewById(R.id.archiveList);
                    ArchiveAdapter adapter = getArchiveAdapter(list);
                    if (adapter != null) {
                        adapter.removeRow(row);
                        if (!archiveHasMore) {
                            updateArchiveFooter(false, adapter.getRawCount());
                        }
                    }
                    queueArchiveDelete(row, root);
                })
                .start();
    }

    /**
     * Hold a delete for a few seconds so it can be undone, then commit it.
     *
     * Deferred rather than confirmed with a dialog: a dialog would reintroduce exactly the friction
     * the owner asked to remove. Nothing is written to the database until the window expires, so an
     * undo is a cancelled timer rather than a re-insert, and the archive's append-only history is
     * never actually disturbed.
     */
    private void queueArchiveDelete(final ArchiveRow row, final View root) {
        pendingDeletes.add(row);
        final Runnable commit = () -> {
            if (!pendingDeletes.remove(row)) return;
            NotificationArchive.deleteRow(activity, row.id);
            if (pendingDeletes.isEmpty()) hideUndoBar(root);
            renderArchiveSummaryOnly(root);
        };
        pendingDeleteCommits.put(row, commit);
        handler.postDelayed(commit, UNDO_WINDOW_MS);
        showUndoBar(root, row);
    }

    /** Cancel every queued delete that has not yet committed. */
    private void undoArchiveDeletes(final View root) {
        for (Runnable commit : pendingDeleteCommits.values()) handler.removeCallbacks(commit);
        pendingDeleteCommits.clear();
        int restored = pendingDeletes.size();
        pendingDeletes.clear();
        hideUndoBar(root);
        if (archiveSearchRunnable != null) handler.removeCallbacks(archiveSearchRunnable);
        renderArchive(root);
        toast(restored == 1 ? "Kept it. Close call."
                            : "Kept all " + restored + " of them. Close call.");
    }

    /**
     * Commit any queued deletes immediately.
     *
     * Called when the Vault closes. The owner swiped deliberately, so leaving the screen confirms
     * the intent rather than cancelling it; the alternative would be a delete that silently never
     * happened.
     */
    private void flushArchiveDeletes() {
        for (Runnable commit : pendingDeleteCommits.values()) handler.removeCallbacks(commit);
        pendingDeleteCommits.clear();
        for (ArchiveRow row : pendingDeletes) {
            try { NotificationArchive.deleteRow(activity, row.id); } catch (Throwable ignored) { }
        }
        pendingDeletes.clear();
    }

    private void showUndoBar(final View root, ArchiveRow row) {
        View bar = root.findViewById(R.id.archiveUndoBar);
        TextView label = root.findViewById(R.id.archiveUndoLabel);
        View button = root.findViewById(R.id.archiveUndoButton);
        if (bar == null || label == null || button == null) return;
        int count = pendingDeletes.size();
        label.setText(count == 1
                ? "Deleted \u201c" + shortened(row.body) + "\u201d"
                : count + " deleted");
        bar.setVisibility(View.VISIBLE);
        button.setOnClickListener(v -> undoArchiveDeletes(root));
    }

    private void hideUndoBar(View root) {
        View bar = root.findViewById(R.id.archiveUndoBar);
        if (bar != null) bar.setVisibility(View.GONE);
    }

    private String shortened(String text) {
        if (text == null || text.isEmpty()) return "that one";
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= 24 ? flat : flat.substring(0, 24) + "\u2026";
    }

    /** Refresh only the counters, so a committed delete does not rebuild the whole list. */
    private void renderArchiveSummaryOnly(View root) {
        TextView summary = root.findViewById(R.id.archiveSummary);
        if (summary == null) return;
        if (archiveQueryExecutor.isShutdown()) return;
        final Context context = activity;
        archiveQueryExecutor.execute(() -> {
            int total = NotificationArchive.totalMessages(context);
            int flagged = NotificationArchive.totalFlagged(context);
            long sizeBytes = NotificationArchive.sizeBytes(context);
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                TextView curSummary = root.findViewById(R.id.archiveSummary);
                if (curSummary != null) {
                    curSummary.setText(total + " LOGGED \u00b7 " + flagged + " INCRIMINATING \u00b7 "
                            + ArchiveSchema.readableSize(sizeBytes));
                }
            });
        });
    }

    /** Rows swiped away but not yet committed to the database. */
    private final java.util.Set<ArchiveRow> pendingDeletes =
            new java.util.LinkedHashSet<>();
    private final java.util.Map<ArchiveRow, Runnable> pendingDeleteCommits =
            new java.util.LinkedHashMap<>();

    /** How long a swiped row can be recovered. Long enough to react, short enough not to linger. */
    private static final long UNDO_WINDOW_MS = 5000L;

    /** One-time explanation of what the archive keeps. Informational, not a gate. */
    private void maybeShowArchiveNotice(AppSettings settings) {
        if (settings.archiveNoticeShown()) return;
        settings.setArchiveNoticeShown(true);
        new AlertDialog.Builder(activity)
                .setTitle("So, about this vault")
                .setMessage("I keep notifications from the apps you pick, STG, so that when "
                        + "somebody deletes a message you can still read exactly what they "
                        + "wrote. Yes, this is a little sinister. You asked for it.\n\n"
                        + "\u2022 It never leaves this phone. No cloud, no backup, no exceptions.\n"
                        + "\u2022 I cannot edit or quietly delete entries \u2014 the database "
                        + "physically refuses. Only you can remove things.\n"
                        + "\u2022 Clearing app data or uninstalling me destroys all of it. Export "
                        + "if you want it to survive my death.\n"
                        + "\u2022 If a message never raised a notification, I never saw it. I'm "
                        + "nosy, not omniscient.")
                .setPositiveButton("Understood, continue being creepy", null)
                .show();
    }

    private ArchiveAdapter getArchiveAdapter(ListView list) {
        if (list == null) return null;
        android.widget.ListAdapter a = list.getAdapter();
        if (a instanceof android.widget.HeaderViewListAdapter) {
            a = ((android.widget.HeaderViewListAdapter) a).getWrappedAdapter();
        }
        return a instanceof ArchiveAdapter ? (ArchiveAdapter) a : null;
    }

    private ArchiveConversationAdapter getConversationAdapter(ListView list) {
        if (list == null) return null;
        android.widget.ListAdapter a = list.getAdapter();
        if (a instanceof android.widget.HeaderViewListAdapter) {
            a = ((android.widget.HeaderViewListAdapter) a).getWrappedAdapter();
        }
        return a instanceof ArchiveConversationAdapter ? (ArchiveConversationAdapter) a : null;
    }

    private NotificationArchive.Filter buildArchiveFilter(View root) {
        EditText search = root.findViewById(R.id.archiveSearch);

        NotificationArchive.Filter filter = new NotificationArchive.Filter();
        if (search != null) {
            String term = search.getText().toString().trim();
            if (!term.isEmpty()) { filter.search = term; }
        }
        filter.quickFilter = archiveQuickFilter;
        filter.flaggedOnly = (archiveQuickFilter == NotificationArchive.Filter.QUICK_FLAGGED);
        long[] dateRange = getArchiveDateRange();
        filter.fromMs = dateRange[0];
        filter.toMs = dateRange[1];
        filter.conversationPkg = archiveCurrentThreadPkg;
        filter.conversationThread = archiveCurrentThreadTitle;
        // Markers are internal system diagnostics and should never clutter the communication vault
        filter.includeMarkers = false;
        return filter;
    }

    private View createArchiveFooterView(final View root) {
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(android.view.Gravity.CENTER);
        container.setPadding(0, dp(12), 0, dp(24));

        TextView text = new TextView(activity);
        text.setText("Load more receipts \u25BC");
        text.setTextSize(11f);
        text.setTextColor(getColor(R.color.deck_accent));
        text.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        text.setGravity(android.view.Gravity.CENTER);
        text.setPadding(dp(16), dp(8), dp(16), dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.deck_card));
        bg.setCornerRadius(dp(4));
        bg.setStroke(dp(1), getColor(R.color.deck_border));
        text.setBackground(bg);

        container.addView(text);
        archiveFooterText = text;

        container.setOnClickListener(v -> loadMoreArchiveRows(root));
        return container;
    }

    private void updateArchiveFooter(boolean hasMore, int loadedCount) {
        if (archiveFooterText == null) return;
        if (archiveFooterView != null) archiveFooterView.setVisibility(View.VISIBLE);
        if (hasMore) {
            archiveFooterText.setText("Load more receipts \u25BC");
            archiveFooterText.setTextColor(getColor(R.color.deck_accent));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(getColor(R.color.deck_card));
            bg.setCornerRadius(dp(4));
            bg.setStroke(dp(1), getColor(R.color.deck_border));
            archiveFooterText.setBackground(bg);
        } else {
            if (loadedCount > 0) {
                archiveFooterText.setText("\u2500\u2500 All " + loadedCount
                        + (loadedCount == 1 ? " receipt" : " receipts") + " loaded \u2500\u2500");
                archiveFooterText.setTextColor(getColor(R.color.deck_text_dim));
                archiveFooterText.setBackground(null);
            } else {
                if (archiveFooterView != null) archiveFooterView.setVisibility(View.GONE);
            }
        }
    }

    private void loadMoreArchiveRows(final View root) {
        if (archiveViewMode == ARCHIVE_VIEW_CONVERSATIONS && archiveCurrentThreadTitle == null) return;
        if (!archiveHasMore || archiveIsLoadingMore) return;
        if (archiveQueryExecutor.isShutdown()) return;
        final ListView list = root.findViewById(R.id.archiveList);
        final ArchiveAdapter adapter = getArchiveAdapter(list);
        if (adapter == null) return;

        archiveIsLoadingMore = true;
        if (archiveFooterText != null) {
            archiveFooterText.setText("Loading more receipts\u2026");
            archiveFooterText.setTextColor(getColor(R.color.deck_text_dim));
        }

        final int offset = adapter.getRawCount();
        final NotificationArchive.Filter filter = buildArchiveFilter(root);
        filter.limit = ARCHIVE_PAGE_SIZE + 1;
        filter.offset = offset;

        final java.util.List<Long> pendingIds = new java.util.ArrayList<>();
        for (ArchiveRow p : pendingDeletes) pendingIds.add(p.id);

        final long queryId = archiveQuerySequence;
        final Context context = activity;

        archiveQueryExecutor.execute(() -> {
            java.util.List<ArchiveRow> batch = NotificationArchive.query(context, filter);

            if (!pendingIds.isEmpty()) {
                java.util.List<ArchiveRow> filtered = new java.util.ArrayList<>();
                for (ArchiveRow r : batch) {
                    if (!pendingIds.contains(r.id)) filtered.add(r);
                }
                batch = filtered;
            }

            final boolean hasMore = batch.size() > ARCHIVE_PAGE_SIZE;
            if (hasMore) {
                batch = new java.util.ArrayList<>(batch.subList(0, ARCHIVE_PAGE_SIZE));
            }
            final java.util.List<ArchiveRow> finalBatch = batch;

            handler.post(() -> {
                archiveIsLoadingMore = false;
                if (queryId != archiveQuerySequence) return;
                if (isFinishing() || isDestroyed()) return;

                if (!finalBatch.isEmpty()) {
                    adapter.addItems(finalBatch);
                }
                archiveHasMore = hasMore;
                updateArchiveFooter(archiveHasMore, adapter.getRawCount());
            });
        });
    }

    private static class ArchiveQueryResult {
        final long queryId;
        final java.util.List<ArchiveRow> rows;
        final java.util.List<ConversationGroup> conversations;
        final int total;
        final int flagged;
        final long sizeBytes;
        final ArchiveSchema.Usage usage;
        final String usageMessage;
        final NotificationArchive.Status health;
        final boolean accessGranted;
        final boolean hasMore;

        ArchiveQueryResult(long queryId, java.util.List<ArchiveRow> rows,
                           java.util.List<ConversationGroup> conversations,
                           int total, int flagged,
                           long sizeBytes, ArchiveSchema.Usage usage, String usageMessage,
                           NotificationArchive.Status health, boolean accessGranted, boolean hasMore) {
            this.queryId = queryId;
            this.rows = rows;
            this.conversations = conversations;
            this.total = total;
            this.flagged = flagged;
            this.sizeBytes = sizeBytes;
            this.usage = usage;
            this.usageMessage = usageMessage;
            this.health = health;
            this.accessGranted = accessGranted;
            this.hasMore = hasMore;
        }
    }

    /** Re-read the archive and rebuild the card list against the current filters. */
    private void renderArchive(View root) {
        ListView list = root.findViewById(R.id.archiveList);
        TextView emptyText = root.findViewById(R.id.archiveEmptyText);
        TextView summary = root.findViewById(R.id.archiveSummary);
        TextView warning = root.findViewById(R.id.archiveWarning);
        if (list == null) return;
        if (archiveQueryExecutor.isShutdown()) return;

        final boolean isRootConvView = (archiveViewMode == ARCHIVE_VIEW_CONVERSATIONS && archiveCurrentThreadTitle == null);
        final NotificationArchive.Filter filter = buildArchiveFilter(root);
        filter.limit = ARCHIVE_PAGE_SIZE + 1;
        filter.offset = 0;

        // Snapshot pending delete IDs on UI thread to avoid concurrent access to pendingDeletes set
        final java.util.List<Long> pendingIds = new java.util.ArrayList<>();
        for (ArchiveRow p : pendingDeletes) {
            pendingIds.add(p.id);
        }

        final long queryId = ++archiveQuerySequence;
        archiveIsLoadingMore = false;
        final boolean accessGranted = notificationAccessGranted();
        final boolean isConnected = IrisNotificationListener.isConnected();
        final Context context = activity;

        archiveQueryExecutor.execute(() -> {
            java.util.List<ArchiveRow> rows = java.util.Collections.emptyList();
            java.util.List<ConversationGroup> convs = java.util.Collections.emptyList();
            boolean hasMore = false;

            if (isRootConvView) {
                convs = NotificationArchive.queryConversations(context, filter);
            } else {
                rows = NotificationArchive.query(context, filter);

                // Filter out any rows pending delete in the undo window
                if (!pendingIds.isEmpty()) {
                    java.util.List<ArchiveRow> filtered = new java.util.ArrayList<>();
                    for (ArchiveRow r : rows) {
                        if (!pendingIds.contains(r.id)) {
                            filtered.add(r);
                        }
                    }
                    rows = filtered;
                }

                hasMore = rows.size() > ARCHIVE_PAGE_SIZE;
                if (hasMore) {
                    rows = new java.util.ArrayList<>(rows.subList(0, ARCHIVE_PAGE_SIZE));
                }
            }

            int total = NotificationArchive.totalMessages(context);
            int flagged = NotificationArchive.totalFlagged(context);
            long sizeBytes = NotificationArchive.sizeBytes(context);
            ArchiveSchema.Usage usage = NotificationArchive.usage(context);
            String usageMessage = (usage == ArchiveSchema.Usage.CRITICAL || usage == ArchiveSchema.Usage.FULL)
                    ? NotificationArchive.usageMessage(context) : "";
            NotificationArchive.Status health = NotificationArchive.status(context, accessGranted, isConnected);

            final ArchiveQueryResult result = new ArchiveQueryResult(
                    queryId, rows, convs, total, flagged, sizeBytes, usage, usageMessage, health, accessGranted, hasMore);

            handler.post(() -> {
                // Ignore stale results if another query was dispatched
                if (result.queryId != archiveQuerySequence) return;
                if (isFinishing() || isDestroyed()) return;

                if (summary != null) {
                    String dateSuffix = (archiveDatePreset != DATE_PRESET_ALL) ? (" \u00b7 " + getArchiveDateRangeLabel()) : "";
                    if (isRootConvView) {
                        summary.setText(result.conversations.size() + " CHATS" + dateSuffix + " \u00b7 "
                                + result.flagged + " INCRIMINATING \u00b7 "
                                + ArchiveSchema.readableSize(result.sizeBytes));
                    } else if (archiveCurrentThreadTitle != null) {
                        summary.setText("THREAD \u00b7 " + result.rows.size() + " LOADED" + dateSuffix + " \u00b7 "
                                + ArchiveSchema.readableSize(result.sizeBytes));
                    } else {
                        summary.setText(result.total + " LOGGED" + dateSuffix + " \u00b7 "
                                + result.flagged + " INCRIMINATING \u00b7 "
                                + ArchiveSchema.readableSize(result.sizeBytes));
                    }
                }
                if (warning != null) {
                    boolean show = result.usage == ArchiveSchema.Usage.CRITICAL || result.usage == ArchiveSchema.Usage.FULL;
                    warning.setText(result.usageMessage);
                    warning.setVisibility(show ? View.VISIBLE : View.GONE);
                }

                // Capture health. Every failure path in the archive is deliberately silent so it cannot
                // crash the notification listener, which made an empty list ambiguous: not switched on, no
                // apps ticked, access not granted, reader not bound, or a storage fault all looked the
                // same. This says which, so the owner is not left guessing.
                TextView status = root.findViewById(R.id.archiveStatus);
                if (status != null) {
                    status.setText(result.health.text);
                    status.setTextColor(getColor(result.health.problem ? R.color.deck_error : R.color.deck_text_dim));
                }

                // Offer the grant button only while access is actually missing, so the screen is not
                // permanently nagging about a permission that is already in place.
                View grant = root.findViewById(R.id.archiveGrantButton);
                if (grant != null) grant.setVisibility(result.accessGranted ? View.GONE : View.VISIBLE);

                if (emptyText != null) {
                    if (result.total == 0) {
                        emptyText.setText("Nothing in the vault yet. Tick some apps and let people incriminate "
                                + "themselves at their own pace.");
                    } else if (archiveDatePreset != DATE_PRESET_ALL && ((isRootConvView && result.conversations.isEmpty()) || (!isRootConvView && result.rows.isEmpty()))) {
                        emptyText.setText("No receipts found for " + getArchiveDateRangeLabel()
                                + ". Try switching to [ALL TIME] or expanding your date range.");
                    } else if (isRootConvView) {
                        if (archiveQuickFilter == NotificationArchive.Filter.QUICK_FLAGGED) {
                            emptyText.setText("No conversations with incriminating evidence found.");
                        } else if (archiveQuickFilter == NotificationArchive.Filter.QUICK_PHOTOS) {
                            emptyText.setText("No conversations with photos captured found.");
                        } else if (archiveQuickFilter == NotificationArchive.Filter.QUICK_VIDEOS) {
                            emptyText.setText("No conversations with video clips found.");
                        } else if (archiveQuickFilter == NotificationArchive.Filter.QUICK_AUDIO) {
                            emptyText.setText("No conversations with voice notes found.");
                        } else if (archiveQuickFilter == NotificationArchive.Filter.QUICK_DOCS) {
                            emptyText.setText("No conversations with documents found.");
                        } else if (archiveQuickFilter == NotificationArchive.Filter.QUICK_WHATSAPP) {
                            emptyText.setText("No WhatsApp conversations found.");
                        } else if (archiveQuickFilter == NotificationArchive.Filter.QUICK_SMS) {
                            emptyText.setText("No SMS conversations found.");
                        } else {
                            emptyText.setText("No conversations match your search or filter.");
                        }
                    } else if (archiveCurrentThreadTitle != null) {
                        emptyText.setText("No receipts found in this conversation matching your filter.");
                    } else if (archiveQuickFilter == NotificationArchive.Filter.QUICK_FLAGGED) {
                        emptyText.setText("No incriminating evidence found. Either your search is wrong or "
                                + "everyone's suspiciously well behaved.");
                    } else if (archiveQuickFilter == NotificationArchive.Filter.QUICK_PHOTOS) {
                        emptyText.setText("No photos captured in the vault matching this query.");
                    } else if (archiveQuickFilter == NotificationArchive.Filter.QUICK_VIDEOS) {
                        emptyText.setText("No video clips captured in the vault matching this query.");
                    } else if (archiveQuickFilter == NotificationArchive.Filter.QUICK_AUDIO) {
                        emptyText.setText("No voice notes or audio recordings found in the vault matching this query.");
                    } else if (archiveQuickFilter == NotificationArchive.Filter.QUICK_DOCS) {
                        emptyText.setText("No document attachments found in the vault matching this query.");
                    } else if (archiveQuickFilter == NotificationArchive.Filter.QUICK_WHATSAPP) {
                        emptyText.setText("No WhatsApp notifications found matching this query.");
                    } else if (archiveQuickFilter == NotificationArchive.Filter.QUICK_SMS) {
                        emptyText.setText("No SMS or text messages found matching this query.");
                    } else {
                        emptyText.setText("Nothing matches that. Either your search is wrong or everyone's "
                                + "suspiciously well behaved.");
                    }
                }

                if (isRootConvView) {
                    ArchiveConversationAdapter convAdapter = getConversationAdapter(list);
                    if (convAdapter == null) {
                        convAdapter = new ArchiveConversationAdapter(root);
                        list.setAdapter(convAdapter);
                        if (emptyText != null) list.setEmptyView(emptyText);
                    }
                    convAdapter.setItems(result.conversations);
                    archiveHasMore = false;
                    updateArchiveFooter(false, convAdapter.getCount());
                } else {
                    ArchiveAdapter adapter = getArchiveAdapter(list);
                    if (adapter == null) {
                        adapter = new ArchiveAdapter(root);
                        list.setAdapter(adapter);
                        if (emptyText != null) list.setEmptyView(emptyText);
                    }
                    adapter.setItems(result.rows);
                    archiveHasMore = result.hasMore;
                    updateArchiveFooter(archiveHasMore, adapter.getRawCount());
                }
            });
        });
    }

    private static class ArchiveDisplayItem {
        static final int TYPE_MESSAGE = 0;
        static final int TYPE_DATE_SEPARATOR = 1;

        final int type;
        final ArchiveRow row;
        final String dateLabel;

        ArchiveDisplayItem(ArchiveRow row) {
            this.type = TYPE_MESSAGE;
            this.row = row;
            this.dateLabel = null;
        }

        ArchiveDisplayItem(String dateLabel) {
            this.type = TYPE_DATE_SEPARATOR;
            this.row = null;
            this.dateLabel = dateLabel;
        }
    }

    private static class ArchiveViewHolder {
        LinearLayout card;
        TextView head;
        TextView meta;
        TextView body;
        TextView expandToggle;
        FrameLayout mediaContainer;
        ImageView shot;
        TextView playBadge;
        TextView missingPhoto;
        LinearLayout audioContainer;
        TextView audioPlayBtn;
        TextView audioTitle;
        TextView audioTime;
        WaveformView audioWaveform;
        TextView audioSaveBtn;
        LinearLayout docContainer;
        TextView docBadge;
        TextView docTitle;
        TextView docMeta;
        TextView docOpenBtn;
        TextView docSaveBtn;
        TextView status;
        TextView caveat;
        LinearLayout quickActionsBar;
        TextView btnCopy;
        TextView btnShare;
        TextView btnPin;
        TextView btnFlag;
        TextView btnDelete;
    }

    private class ArchiveAdapter extends BaseAdapter {
        private final java.util.List<ArchiveRow> rawRows = new java.util.ArrayList<>();
        private final java.util.List<ArchiveDisplayItem> displayItems = new java.util.ArrayList<>();
        private final View root;

        ArchiveAdapter(View root) {
            this.root = root;
        }

        void setItems(java.util.List<ArchiveRow> newItems) {
            rawRows.clear();
            if (newItems != null) rawRows.addAll(newItems);
            rebuildDisplayItems();
            notifyDataSetChanged();
        }

        void addItems(java.util.List<ArchiveRow> newItems) {
            if (newItems == null || newItems.isEmpty()) return;
            for (ArchiveRow row : newItems) {
                boolean exists = false;
                for (ArchiveRow existing : rawRows) {
                    if (existing.id == row.id) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) rawRows.add(row);
            }
            rebuildDisplayItems();
            notifyDataSetChanged();
        }

        void removeRow(ArchiveRow row) {
            for (int i = 0; i < rawRows.size(); i++) {
                if (rawRows.get(i).id == row.id) {
                    rawRows.remove(i);
                    break;
                }
            }
            rebuildDisplayItems();
            notifyDataSetChanged();
        }

        int getRawCount() {
            return rawRows.size();
        }

        java.util.List<ArchiveRow> getRawRows() {
            return rawRows;
        }

        private boolean isSameDay(Calendar c1, Calendar c2) {
            return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR)
                    && c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR);
        }

        private String formatDateSeparator(long timeMs) {
            if (timeMs <= 0) return "\u2500\u2500 UNKNOWN DATE \u2500\u2500";
            Calendar now = Calendar.getInstance();
            Calendar target = Calendar.getInstance();
            target.setTimeInMillis(timeMs);

            if (isSameDay(now, target)) {
                return "\u2500\u2500 TODAY \u2500\u2500";
            }

            Calendar yesterday = Calendar.getInstance();
            yesterday.add(Calendar.DAY_OF_YEAR, -1);
            if (isSameDay(yesterday, target)) {
                return "\u2500\u2500 YESTERDAY \u2500\u2500";
            }

            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US);
            return "\u2500\u2500 " + sdf.format(new java.util.Date(timeMs)).toUpperCase(java.util.Locale.US) + " \u2500\u2500";
        }

        private void rebuildDisplayItems() {
            displayItems.clear();
            if (rawRows.isEmpty()) return;

            Calendar lastCal = null;
            Calendar curCal = Calendar.getInstance();

            for (ArchiveRow row : rawRows) {
                if (row.postTime > 0) {
                    curCal.setTimeInMillis(row.postTime);
                    if (lastCal == null || !isSameDay(lastCal, curCal)) {
                        displayItems.add(new ArchiveDisplayItem(formatDateSeparator(row.postTime)));
                        lastCal = Calendar.getInstance();
                        lastCal.setTimeInMillis(row.postTime);
                    }
                }
                displayItems.add(new ArchiveDisplayItem(row));
            }
        }

        @Override
        public int getCount() {
            return displayItems.size();
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return displayItems.get(position).type;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return displayItems.get(position).type == ArchiveDisplayItem.TYPE_MESSAGE;
        }

        @Override
        public ArchiveDisplayItem getItem(int position) {
            return displayItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            ArchiveDisplayItem item = displayItems.get(position);
            if (item.type == ArchiveDisplayItem.TYPE_MESSAGE && item.row != null) {
                return item.row.id;
            }
            return -position - 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            int viewType = getItemViewType(position);
            ArchiveDisplayItem item = displayItems.get(position);

            if (viewType == ArchiveDisplayItem.TYPE_DATE_SEPARATOR) {
                TextView sep;
                if (convertView == null) {
                    sep = new TextView(activity);
                    sep.setTextSize(11f);
                    sep.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                    sep.setTextColor(getColor(R.color.deck_accent));
                    sep.setGravity(android.view.Gravity.CENTER);
                    sep.setPadding(dp(12), dp(14), dp(12), dp(6));
                    sep.setLetterSpacing(0.08f);
                    convertView = sep;
                } else {
                    sep = (TextView) convertView;
                }
                sep.setText(item.dateLabel);
                return convertView;
            }

            ArchiveViewHolder holder;
            if (convertView == null) {
                holder = new ArchiveViewHolder();
                LinearLayout card = new LinearLayout(activity);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(12), dp(10), dp(12), dp(10));
                card.setLayoutParams(new AbsListView.LayoutParams(
                        AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT));

                holder.card = card;

                holder.head = new TextView(activity);
                holder.head.setTextSize(13f);
                holder.head.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                card.addView(holder.head);

                holder.meta = new TextView(activity);
                holder.meta.setTextSize(10f);
                holder.meta.setTypeface(android.graphics.Typeface.MONOSPACE);
                holder.meta.setTextColor(getColor(R.color.deck_text_dim));
                card.addView(holder.meta);

                holder.body = new TextView(activity);
                holder.body.setTextSize(13f);
                holder.body.setTextColor(getColor(R.color.deck_text));
                holder.body.setPadding(0, dp(4), 0, 0);
                card.addView(holder.body);

                holder.expandToggle = new TextView(activity);
                holder.expandToggle.setTextSize(10f);
                holder.expandToggle.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                holder.expandToggle.setTextColor(getColor(R.color.deck_accent));
                holder.expandToggle.setPadding(0, dp(4), 0, dp(2));
                holder.expandToggle.setVisibility(View.GONE);
                card.addView(holder.expandToggle);

                holder.mediaContainer = new FrameLayout(activity);
                LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(160));
                containerParams.topMargin = dp(6);
                holder.mediaContainer.setLayoutParams(containerParams);
                holder.mediaContainer.setVisibility(View.GONE);

                holder.shot = new ImageView(activity);
                FrameLayout.LayoutParams shotParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
                holder.shot.setLayoutParams(shotParams);
                holder.shot.setScaleType(ImageView.ScaleType.CENTER_CROP);
                holder.mediaContainer.addView(holder.shot);

                holder.playBadge = new TextView(activity);
                holder.playBadge.setText("\u25B6 VIDEO");
                holder.playBadge.setTextSize(10f);
                holder.playBadge.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                holder.playBadge.setTextColor(getColor(R.color.deck_accent));
                holder.playBadge.setBackgroundResource(R.drawable.bg_cyber_button);
                holder.playBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
                FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM | android.view.Gravity.END);
                badgeParams.setMargins(0, 0, dp(8), dp(8));
                holder.playBadge.setLayoutParams(badgeParams);
                holder.playBadge.setVisibility(View.GONE);
                holder.mediaContainer.addView(holder.playBadge);

                card.addView(holder.mediaContainer);

                holder.audioContainer = new LinearLayout(activity);
                holder.audioContainer.setOrientation(LinearLayout.HORIZONTAL);
                holder.audioContainer.setGravity(android.view.Gravity.CENTER_VERTICAL);
                holder.audioContainer.setBackgroundResource(R.drawable.bg_cyber_panel);
                holder.audioContainer.setPadding(dp(10), dp(8), dp(10), dp(8));
                LinearLayout.LayoutParams audioParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                audioParams.topMargin = dp(6);
                holder.audioContainer.setLayoutParams(audioParams);
                holder.audioContainer.setVisibility(View.GONE);

                holder.audioPlayBtn = new TextView(activity);
                holder.audioPlayBtn.setText("\u25B6");
                holder.audioPlayBtn.setTextSize(14f);
                holder.audioPlayBtn.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                holder.audioPlayBtn.setTextColor(getColor(R.color.deck_accent));
                holder.audioPlayBtn.setGravity(android.view.Gravity.CENTER);
                holder.audioPlayBtn.setBackgroundResource(R.drawable.bg_cyber_button);
                LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(36), dp(36));
                holder.audioPlayBtn.setLayoutParams(playParams);
                holder.audioContainer.addView(holder.audioPlayBtn);

                LinearLayout middleCol = new LinearLayout(activity);
                middleCol.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams middleParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                middleParams.setMargins(dp(10), 0, dp(8), 0);
                middleCol.setLayoutParams(middleParams);

                LinearLayout labelRow = new LinearLayout(activity);
                labelRow.setOrientation(LinearLayout.HORIZONTAL);
                labelRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams labelRowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                labelRow.setLayoutParams(labelRowParams);

                holder.audioTitle = new TextView(activity);
                holder.audioTitle.setText("VOICE NOTE");
                holder.audioTitle.setTextSize(10f);
                holder.audioTitle.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                holder.audioTitle.setTextColor(getColor(R.color.deck_accent));
                LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                holder.audioTitle.setLayoutParams(titleParams);
                labelRow.addView(holder.audioTitle);

                holder.audioTime = new TextView(activity);
                holder.audioTime.setText("0:00");
                holder.audioTime.setTextSize(10f);
                holder.audioTime.setTypeface(android.graphics.Typeface.MONOSPACE);
                holder.audioTime.setTextColor(getColor(R.color.deck_text_dim));
                labelRow.addView(holder.audioTime);

                middleCol.addView(labelRow);

                holder.audioWaveform = new WaveformView(activity);
                LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(22));
                waveParams.topMargin = dp(4);
                holder.audioWaveform.setLayoutParams(waveParams);
                middleCol.addView(holder.audioWaveform);

                holder.audioContainer.addView(middleCol);

                holder.audioSaveBtn = new TextView(activity);
                holder.audioSaveBtn.setText("\u2913");
                holder.audioSaveBtn.setTextSize(14f);
                holder.audioSaveBtn.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                holder.audioSaveBtn.setTextColor(getColor(R.color.deck_text_dim));
                holder.audioSaveBtn.setGravity(android.view.Gravity.CENTER);
                holder.audioSaveBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
                holder.audioSaveBtn.setContentDescription("Save voice note copy");
                holder.audioContainer.addView(holder.audioSaveBtn);

                card.addView(holder.audioContainer);

                holder.docContainer = new LinearLayout(activity);
                holder.docContainer.setOrientation(LinearLayout.HORIZONTAL);
                holder.docContainer.setGravity(android.view.Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams docParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                docParams.topMargin = dp(8);
                docParams.bottomMargin = dp(4);
                holder.docContainer.setLayoutParams(docParams);
                holder.docContainer.setPadding(dp(10), dp(8), dp(10), dp(8));
                GradientDrawable docBg = new GradientDrawable();
                docBg.setColor(getColor(R.color.deck_card));
                docBg.setCornerRadius(dp(8));
                docBg.setStroke(dp(1), getColor(R.color.deck_border));
                holder.docContainer.setBackground(docBg);

                holder.docBadge = new TextView(activity);
                holder.docBadge.setText("DOC");
                holder.docBadge.setTextSize(11f);
                holder.docBadge.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                holder.docBadge.setGravity(android.view.Gravity.CENTER);
                holder.docBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
                GradientDrawable badgeBg = new GradientDrawable();
                badgeBg.setColor(getColor(R.color.deck_card));
                badgeBg.setCornerRadius(dp(4));
                badgeBg.setStroke(dp(1), getColor(R.color.deck_accent));
                holder.docBadge.setBackground(badgeBg);
                holder.docBadge.setTextColor(getColor(R.color.deck_accent));
                holder.docContainer.addView(holder.docBadge);

                LinearLayout docTextCol = new LinearLayout(activity);
                docTextCol.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams docTextParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                docTextParams.setMargins(dp(10), 0, dp(8), 0);
                docTextCol.setLayoutParams(docTextParams);

                holder.docTitle = new TextView(activity);
                holder.docTitle.setText("DOCUMENT");
                holder.docTitle.setTextSize(12f);
                holder.docTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                holder.docTitle.setTextColor(getColor(R.color.deck_text));
                holder.docTitle.setSingleLine(true);
                holder.docTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
                docTextCol.addView(holder.docTitle);

                holder.docMeta = new TextView(activity);
                holder.docMeta.setText("0 KB \u00b7 Tap to open");
                holder.docMeta.setTextSize(10f);
                holder.docMeta.setTypeface(android.graphics.Typeface.MONOSPACE);
                holder.docMeta.setTextColor(getColor(R.color.deck_text_dim));
                docTextCol.addView(holder.docMeta);

                holder.docContainer.addView(docTextCol);

                holder.docOpenBtn = new TextView(activity);
                holder.docOpenBtn.setText("OPEN \u2197");
                holder.docOpenBtn.setTextSize(10f);
                holder.docOpenBtn.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                holder.docOpenBtn.setTextColor(getColor(R.color.deck_accent));
                holder.docOpenBtn.setGravity(android.view.Gravity.CENTER);
                holder.docOpenBtn.setPadding(dp(8), dp(6), dp(8), dp(6));
                GradientDrawable openBg = new GradientDrawable();
                openBg.setColor(getColor(R.color.deck_card));
                openBg.setCornerRadius(dp(4));
                openBg.setStroke(dp(1), getColor(R.color.deck_accent));
                holder.docOpenBtn.setBackground(openBg);
                holder.docOpenBtn.setContentDescription("Open document with external app");
                holder.docContainer.addView(holder.docOpenBtn);

                holder.docSaveBtn = new TextView(activity);
                holder.docSaveBtn.setText("\u2913");
                holder.docSaveBtn.setTextSize(14f);
                holder.docSaveBtn.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                holder.docSaveBtn.setTextColor(getColor(R.color.deck_text_dim));
                holder.docSaveBtn.setGravity(android.view.Gravity.CENTER);
                LinearLayout.LayoutParams docSaveParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                docSaveParams.leftMargin = dp(4);
                holder.docSaveBtn.setLayoutParams(docSaveParams);
                holder.docSaveBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
                holder.docSaveBtn.setContentDescription("Save document copy");
                holder.docContainer.addView(holder.docSaveBtn);

                card.addView(holder.docContainer);

                holder.missingPhoto = new TextView(activity);
                holder.missingPhoto.setText("[ photo recorded but the file is missing ]");
                holder.missingPhoto.setTextSize(10f);
                holder.missingPhoto.setTypeface(android.graphics.Typeface.MONOSPACE);
                holder.missingPhoto.setTextColor(getColor(R.color.deck_text_dim));
                holder.missingPhoto.setPadding(0, dp(6), 0, 0);
                holder.missingPhoto.setVisibility(View.GONE);
                card.addView(holder.missingPhoto);

                holder.status = new TextView(activity);
                holder.status.setTextSize(12f);
                holder.status.setTypeface(null, android.graphics.Typeface.BOLD);
                holder.status.setPadding(0, dp(6), 0, 0);
                holder.status.setVisibility(View.GONE);
                card.addView(holder.status);

                holder.caveat = new TextView(activity);
                holder.caveat.setTextSize(11f);
                holder.caveat.setTextColor(getColor(R.color.text_muted));
                holder.caveat.setVisibility(View.GONE);
                card.addView(holder.caveat);

                holder.quickActionsBar = new LinearLayout(activity);
                holder.quickActionsBar.setOrientation(LinearLayout.HORIZONTAL);
                holder.quickActionsBar.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams qaParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                qaParams.topMargin = dp(8);
                holder.quickActionsBar.setLayoutParams(qaParams);

                holder.btnCopy = createCyberActionButton("\uD83D\uDCCB COPY");
                holder.btnShare = createCyberActionButton("\u2197 SHARE");
                holder.btnPin = createCyberActionButton("\uD83D\uDCCC PIN");
                holder.btnFlag = createCyberActionButton("\uD83D\uDEA9 FLAG");
                holder.btnDelete = createCyberActionButton("\uD83D\uDDD1\uFE0F DEL");

                holder.quickActionsBar.addView(holder.btnCopy);
                holder.quickActionsBar.addView(holder.btnShare);
                holder.quickActionsBar.addView(holder.btnPin);
                holder.quickActionsBar.addView(holder.btnFlag);
                holder.quickActionsBar.addView(holder.btnDelete);
                card.addView(holder.quickActionsBar);

                card.setTag(holder);
                convertView = card;
            } else {
                holder = (ArchiveViewHolder) convertView.getTag();
                convertView.setTranslationX(0f);
                convertView.setAlpha(1f);
            }

            final ArchiveRow row = item.row;
            if (row != null) {
                bindArchiveCard(holder, row, root);
            }
            return convertView;
        }
    }

    /**
     * Bind one archive entry to its recycled view holder.
     *
     * A flagged card is tinted and carries the status line; the original text stays fully visible,
     * which is the entire point. A suspected flag is styled distinctly from a confirmed one so a
     * guess can never be mistaken for a fact.
     */
    private void bindArchiveCard(final ArchiveViewHolder holder, final ArchiveRow row, final View root) {
        DeletedMessageRules.Confidence confidence = row.confidence();
        boolean marker = !row.isMessage();

        // A deleted or unsent message is the whole reason this screen exists, so it gets its own
        // edge strip rather than relying on text colour. Confirmed and suspected deliberately
        // share one style: that distinction is carried by the [STATUS] line, which is worded so a
        // guess cannot read as a fact, and encoding it in colour too would invite reading a colour
        // as proof.
        holder.card.setBackgroundResource(confidence == DeletedMessageRules.Confidence.NONE
                ? R.drawable.bg_cyber_row : R.drawable.bg_cyber_row_flagged);

        String who = row.sender.isEmpty() ? row.rawTitle : row.sender;
        if (who.isEmpty()) who = "Someone who left no name";
        // Resolved at read time, never stored: the table is append-only, and looking up late means
        // a contact saved today also names rows captured weeks ago.
        who = ContactResolver.display(activity, who);
        String where = row.accountLabel.isEmpty() ? row.appLabel : row.accountLabel;
        holder.head.setText(marker ? "\u2014 " + where : who + (row.group.isEmpty() ? "" : " \u00b7 " + row.group));
        holder.head.setTextColor(getColor(confidence == DeletedMessageRules.Confidence.CONFIRMED
                ? R.color.deck_error : R.color.deck_accent));

        holder.body.setText(row.body.isEmpty() ? "(no text \u2014 just vibes)" : row.body);

        if (archiveDensityMode == ARCHIVE_DENSITY_COMPACT) {
            holder.card.setPadding(dp(12), dp(6), dp(12), dp(6));
            holder.body.setMaxLines(1);
            holder.body.setEllipsize(android.text.TextUtils.TruncateAt.END);
            holder.expandToggle.setVisibility(View.GONE);

            if (!marker) {
                StringBuilder metaSb = new StringBuilder();
                metaSb.append(where).append(" \u00b7 ").append(DeletedMessageRules.formatTime(
                        row.postTime, java.util.TimeZone.getDefault()));
                if (row.isAudio()) {
                    metaSb.append(" \u00b7 \uD83C\uDF99\uFE0F AUDIO");
                } else if (row.isDocument()) {
                    metaSb.append(" \u00b7 \uD83D\uDCC4 ").append(row.documentBadge());
                } else if (row.isVideo()) {
                    metaSb.append(" \u00b7 \uD83C\uDFAC VIDEO");
                } else if (row.hasImage()) {
                    metaSb.append(" \u00b7 \uD83D\uDCF7 PHOTO");
                }
                if (confidence != DeletedMessageRules.Confidence.NONE) {
                    metaSb.append(" \u00b7 \uD83D\uDEA9 ").append(DeletedMessageRules.statusLabel(
                            confidence, row.deletionTime, java.util.TimeZone.getDefault()));
                    if (row.deletionTime > row.postTime && row.postTime > 0) {
                        metaSb.append(" (").append(DeletedMessageRules.formatDuration(row.deletionTime - row.postTime)).append(")");
                    }
                }
                holder.meta.setText(metaSb.toString());
                holder.meta.setVisibility(View.VISIBLE);
            } else {
                holder.meta.setVisibility(View.GONE);
            }

            holder.mediaContainer.setVisibility(View.GONE);
            holder.mediaContainer.setTag(null);
            holder.playBadge.setVisibility(View.GONE);
            holder.shot.setImageBitmap(null);
            holder.missingPhoto.setVisibility(View.GONE);
            holder.audioContainer.setVisibility(View.GONE);
            holder.audioContainer.setTag(null);
            holder.docContainer.setVisibility(View.GONE);
            holder.docContainer.setTag(null);
            holder.status.setVisibility(View.GONE);
            holder.caveat.setVisibility(View.GONE);
            holder.quickActionsBar.setVisibility(View.GONE);

            if (row.hasImage()) {
                holder.card.setOnClickListener(v -> {
                    if (row.isAudio()) {
                        toggleArchiveAudioPlayback(row, holder);
                    } else if (row.isDocument()) {
                        openArchiveDocument(row);
                    } else {
                        showArchiveImage(row);
                    }
                });
            } else {
                holder.card.setOnClickListener(null);
            }
        } else {
            // Detailed Mode
            holder.card.setPadding(dp(12), dp(10), dp(12), dp(10));
            holder.card.setOnClickListener(null);

            if (!marker) {
                holder.meta.setVisibility(View.VISIBLE);
                holder.meta.setText(where + " \u00b7 " + DeletedMessageRules.formatTime(
                        row.postTime, java.util.TimeZone.getDefault()));
            } else {
                holder.meta.setVisibility(View.GONE);
            }

            boolean isLong = row.body.length() > 160 || (row.body.contains("\n") && row.body.split("\n").length > 3);
            if (isLong) {
                boolean expanded = archiveExpandedRowIds.contains(row.id);
                holder.expandToggle.setVisibility(View.VISIBLE);
                if (expanded) {
                    holder.body.setMaxLines(Integer.MAX_VALUE);
                    holder.body.setEllipsize(null);
                    holder.expandToggle.setText("[ SHOW LESS \u25B2 ]");
                } else {
                    holder.body.setMaxLines(4);
                    holder.body.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    holder.expandToggle.setText("[ SHOW MORE \u25BC ]");
                }
                holder.expandToggle.setOnClickListener(v -> {
                    if (archiveExpandedRowIds.contains(row.id)) {
                        archiveExpandedRowIds.remove(row.id);
                    } else {
                        archiveExpandedRowIds.add(row.id);
                    }
                    ListView list = root.findViewById(R.id.archiveList);
                    ArchiveAdapter adapter = getArchiveAdapter(list);
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                });
            } else {
                holder.body.setMaxLines(Integer.MAX_VALUE);
                holder.body.setEllipsize(null);
                holder.expandToggle.setVisibility(View.GONE);
            }

            // A captured photo, video, voice note, or document. Decoded asynchronously with LruCache rather than blocking the main thread.
            if (row.isAudio()) {
                holder.mediaContainer.setVisibility(View.GONE);
                holder.mediaContainer.setTag(null);
                holder.playBadge.setVisibility(View.GONE);
                holder.shot.setImageBitmap(null);
                holder.missingPhoto.setVisibility(View.GONE);
                holder.docContainer.setVisibility(View.GONE);
                holder.docContainer.setTag(null);

                holder.audioContainer.setVisibility(View.VISIBLE);
                holder.audioContainer.setTag(row.imagePath);

                String ext = "";
                int dot = row.imagePath.lastIndexOf('.');
                if (dot >= 0) ext = row.imagePath.substring(dot + 1).toUpperCase(java.util.Locale.US);
                holder.audioTitle.setText(ext.isEmpty() ? "VOICE NOTE" : "VOICE NOTE \u00b7 " + ext);
                holder.audioWaveform.setSeed(row.imagePath.hashCode());

                boolean isCurrent = row.imagePath.equals(currentlyPlayingAudioPath);
                if (isCurrent && archivePlayer != null && archivePlayer.isPlaying()) {
                    holder.audioPlayBtn.setText("\u23F8");
                    currentlyPlayingAudioHolder = holder;
                    int current = archivePlayer.getCurrentPosition();
                    int total = archivePlayer.getDuration();
                    float frac = total > 0 ? (float) current / (float) total : 0f;
                    holder.audioWaveform.setProgress(frac);
                    holder.audioTime.setText(ArchiveImages.formatDuration(current) + " / " + ArchiveImages.formatDuration(total));
                } else {
                    holder.audioPlayBtn.setText("\u25B6");
                    holder.audioWaveform.setProgress(0f);
                    long dur = ArchiveImages.mediaDurationMs(activity, row.imagePath);
                    holder.audioTime.setText(dur > 0 ? ArchiveImages.formatDuration(dur) : "Voice Note");
                }

                holder.audioPlayBtn.setOnClickListener(v -> toggleArchiveAudioPlayback(row, holder));
                holder.audioWaveform.setOnSeekListener(fraction -> {
                    if (row.imagePath.equals(currentlyPlayingAudioPath) && archivePlayer != null) {
                        int total = archivePlayer.getDuration();
                        if (total > 0) {
                            int seekMs = Math.round(fraction * total);
                            archivePlayer.seekTo(seekMs);
                            holder.audioTime.setText(
                                    ArchiveImages.formatDuration(seekMs) + " / " + ArchiveImages.formatDuration(total));
                        }
                    }
                });
                holder.audioSaveBtn.setOnClickListener(v -> saveArchiveAudio(row));
            } else if (row.isDocument()) {
                holder.mediaContainer.setVisibility(View.GONE);
                holder.mediaContainer.setTag(null);
                holder.playBadge.setVisibility(View.GONE);
                holder.shot.setImageBitmap(null);
                holder.missingPhoto.setVisibility(View.GONE);
                holder.audioContainer.setVisibility(View.GONE);
                holder.audioContainer.setTag(null);

                holder.docContainer.setVisibility(View.VISIBLE);
                holder.docContainer.setTag(row.imagePath);

                String badge = row.documentBadge();
                holder.docBadge.setText(row.documentIcon() + " " + badge);

                int badgeColor;
                if ("PDF".equals(badge)) {
                    badgeColor = getColor(R.color.deck_error);
                } else if ("DOC".equals(badge)) {
                    badgeColor = getColor(R.color.deck_accent);
                } else if ("XLS".equals(badge) || "CSV".equals(badge)) {
                    badgeColor = getColor(R.color.deck_healthy);
                } else if ("PPT".equals(badge)) {
                    badgeColor = getColor(R.color.deck_attention);
                } else {
                    badgeColor = getColor(R.color.deck_accent);
                }
                holder.docBadge.setTextColor(badgeColor);
                GradientDrawable badgeBg = new GradientDrawable();
                badgeBg.setColor(getColor(R.color.deck_card));
                badgeBg.setCornerRadius(dp(4));
                badgeBg.setStroke(dp(1), badgeColor);
                holder.docBadge.setBackground(badgeBg);

                holder.docTitle.setText(row.documentExtension() + " ATTACHMENT");
                long size = ArchiveImages.fileSizeBytes(activity, row.imagePath);
                holder.docMeta.setText(ArchiveImages.formatFileSize(size) + " \u00b7 Tap to open");

                holder.docContainer.setOnClickListener(v -> openArchiveDocument(row));
                holder.docOpenBtn.setOnClickListener(v -> openArchiveDocument(row));
                holder.docSaveBtn.setOnClickListener(v -> saveArchiveDocument(row));
            } else if (row.hasImage()) {
                holder.audioContainer.setVisibility(View.GONE);
                holder.audioContainer.setTag(null);
                holder.docContainer.setVisibility(View.GONE);
                holder.docContainer.setTag(null);
                final String imagePath = row.imagePath;
                holder.mediaContainer.setTag(imagePath);
                holder.shot.setContentDescription(row.isVideo() ? "Captured video. Tap to play." : "Captured photo. Tap to open.");
                holder.mediaContainer.setOnClickListener(v -> showArchiveImage(row));
                holder.shot.setOnClickListener(v -> showArchiveImage(row));
                holder.playBadge.setVisibility(row.isVideo() ? View.VISIBLE : View.GONE);

                Bitmap cached = ArchiveImages.cachedThumbnail(imagePath);
                if (cached != null) {
                    holder.shot.setImageBitmap(cached);
                    holder.shot.setBackground(null);
                    holder.mediaContainer.setVisibility(View.VISIBLE);
                    holder.missingPhoto.setVisibility(View.GONE);
                } else {
                    // Render subtle cyber placeholder while loading on background worker pool
                    holder.shot.setImageBitmap(null);
                    holder.shot.setBackgroundResource(R.drawable.bg_cyber_panel);
                    holder.mediaContainer.setVisibility(View.VISIBLE);
                    holder.missingPhoto.setVisibility(View.GONE);

                    ArchiveImages.loadThumbnailAsync(activity, imagePath, dp(220), bitmap -> {
                        // Only apply if the recycled view is still presenting the same image
                        if (!imagePath.equals(holder.mediaContainer.getTag())) return;
                        if (bitmap != null) {
                            holder.shot.setImageBitmap(bitmap);
                            holder.shot.setBackground(null);
                            holder.mediaContainer.setVisibility(View.VISIBLE);
                            holder.missingPhoto.setVisibility(View.GONE);
                        } else {
                            holder.mediaContainer.setVisibility(View.GONE);
                            holder.shot.setImageBitmap(null);
                            holder.shot.setBackground(null);
                            holder.missingPhoto.setText(row.isVideo()
                                    ? "[ video recorded but the file is missing ]"
                                    : "[ photo recorded but the file is missing ]");
                            holder.missingPhoto.setVisibility(View.VISIBLE);
                        }
                    });
                }
            } else {
                holder.audioContainer.setVisibility(View.GONE);
                holder.audioContainer.setTag(null);
                holder.docContainer.setVisibility(View.GONE);
                holder.docContainer.setTag(null);
                holder.mediaContainer.setVisibility(View.GONE);
                holder.playBadge.setVisibility(View.GONE);
                holder.shot.setImageBitmap(null);
                holder.shot.setBackground(null);
                holder.mediaContainer.setTag(null);
                holder.missingPhoto.setVisibility(View.GONE);
            }

            if (confidence != DeletedMessageRules.Confidence.NONE) {
                holder.status.setVisibility(View.VISIBLE);
                holder.status.setText(DeletedMessageRules.statusLabel(
                        confidence, row.postTime, row.deletionTime, java.util.TimeZone.getDefault()));
                holder.status.setTextColor(getColor(confidence == DeletedMessageRules.Confidence.CONFIRMED
                        ? R.color.danger : R.color.magenta));

                holder.caveat.setVisibility(View.VISIBLE);
                holder.caveat.setText(DeletedMessageRules.flagCaveat(confidence));
            } else {
                holder.status.setVisibility(View.GONE);
                holder.caveat.setVisibility(View.GONE);
            }

            holder.quickActionsBar.setVisibility(View.VISIBLE);
            holder.btnCopy.setOnClickListener(v -> {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    android.content.ClipData clip = android.content.ClipData.newPlainText("Receipt", row.body);
                    clipboard.setPrimaryClip(clip);
                    toast("Copied receipt text");
                }
            });
            holder.btnShare.setOnClickListener(v -> shareArchiveReceipt(row));
            final String pinSender = who;
            holder.btnPin.setOnClickListener(v -> {
                FlaggedStore.get(activity).addPinned(
                        row.body, pinSender, row.displayAccount(), "Archived message pinned", System.currentTimeMillis());
                toast("Pinned to assistant queue");
            });
            holder.btnFlag.setOnClickListener(v -> {
                FlaggedStore.get(activity).addFlagged(
                        row.body, pinSender, row.displayAccount(), "Archived message flagged", System.currentTimeMillis());
                toast("Flagged as urgent");
            });
            holder.btnDelete.setOnClickListener(v -> {
                new AlertDialog.Builder(activity)
                        .setTitle("Delete this entry?")
                        .setMessage("Remove this archived message permanently?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (d, w) -> {
                            if (NotificationArchive.deleteRow(activity, row.id)) {
                                toast("Gone. I never saw it.");
                                if (archiveSearchRunnable != null) handler.removeCallbacks(archiveSearchRunnable);
                                renderArchive(root);
                            } else {
                                toast("Could not delete that entry.");
                            }
                        })
                        .show();
            });
        }

        holder.card.setContentDescription((confidence == DeletedMessageRules.Confidence.CONFIRMED
                ? "Unsent message from " : "Message from ") + who + ": " + row.body);

        // Do not attach OnTouchListener to child cards: an OnTouchListener on row cards hijacks
        // the initial ACTION_DOWN touch stream and prevents the parent ListView from scrolling fluidly.
        // We use a clean OnClickListener for preview/expansion, OnLongClickListener for full actions,
        // and the dedicated [🗑️ DEL] quick-action button on every card for instant permanent deletion.
        holder.card.setOnClickListener(v -> {
            if (row.hasImage() && !row.isAudio() && !row.isDocument()) {
                showArchiveImage(row);
            } else if (archiveDensityMode == ARCHIVE_DENSITY_COMPACT) {
                if (archiveExpandedRowIds.contains(row.id)) {
                    archiveExpandedRowIds.remove(row.id);
                } else {
                    archiveExpandedRowIds.add(row.id);
                }
                ArchiveAdapter adapter = getArchiveAdapter(root.findViewById(R.id.archiveList));
                if (adapter != null) adapter.notifyDataSetChanged();
            }
        });

        final String archiveSender = who;
        holder.card.setOnLongClickListener(v -> {
            java.util.List<String> act = new java.util.ArrayList<>();
            act.add("\uD83D\uDCCC Pin to assistant queue");
            act.add("\uD83D\uDEA9 Flag as urgent");
            if (row.hasImage()) {
                if (row.isAudio()) {
                    act.add("\uD83D\uDCBE Save audio copy");
                } else if (row.isDocument()) {
                    act.add("\uD83D\uDCC2 Open with\u2026");
                    act.add("\uD83D\uDCBE Save document copy");
                } else if (row.isVideo()) {
                    act.add("\uD83D\uDCBE Save video copy");
                } else {
                    act.add("\uD83D\uDCBE Save photo copy");
                }
            }
            act.add("\uD83D\uDDD1\uFE0F Delete this entry");
            new AlertDialog.Builder(activity)
                    .setTitle(archiveSender)
                    .setItems(act.toArray(new String[0]), (d, which) -> {
                        String sel = act.get(which);
                        if (sel.contains("Pin to assistant queue")) {
                            FlaggedStore.get(activity).addPinned(
                                    row.body, archiveSender, row.displayAccount(), "Archived message pinned", System.currentTimeMillis());
                            toast("Pinned to assistant queue");
                        } else if (sel.contains("Flag as urgent")) {
                            FlaggedStore.get(activity).addFlagged(
                                    row.body, archiveSender, row.displayAccount(), "Archived message flagged", System.currentTimeMillis());
                            toast("Flagged as urgent");
                        } else if (sel.contains("Open with")) {
                            openArchiveDocument(row);
                        } else if (sel.contains("Save audio copy")) {
                            saveArchiveAudio(row);
                        } else if (sel.contains("Save document copy")) {
                            saveArchiveDocument(row);
                        } else if (sel.contains("Save video copy") || sel.contains("Save photo copy")) {
                            showArchiveImage(row);
                        } else {
                            new AlertDialog.Builder(activity)
                                    .setTitle("Delete this entry?")
                                    .setMessage("This removes it permanently. The archive never deletes anything on "
                                            + "its own, so this only happens because you asked.")
                                    .setNegativeButton("Keep it", null)
                                    .setPositiveButton("Delete", (dd, ww) -> {
                                        if (NotificationArchive.deleteRow(activity, row.id)) {
                                            toast("Gone. I never saw it.");
                                            if (archiveSearchRunnable != null) handler.removeCallbacks(archiveSearchRunnable);
                                            renderArchive(root);
                                        } else {
                                            toast("Could not delete that entry.");
                                        }
                                    }).show();
                        }
                    }).show();
            return true;
        });
    }

    private static class ConversationViewHolder {
        LinearLayout card;
        TextView appBadge;
        TextView title;
        TextView time;
        TextView totalBadge;
        TextView unreadBadge;
        TextView flaggedBadge;
        TextView mediaBadges;
        TextView preview;
    }

    private class ArchiveConversationAdapter extends BaseAdapter {
        private final java.util.List<ConversationGroup> items = new java.util.ArrayList<>();
        private final View root;

        ArchiveConversationAdapter(View root) {
            this.root = root;
        }

        void setItems(java.util.List<ConversationGroup> newItems) {
            items.clear();
            if (newItems != null) items.addAll(newItems);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public ConversationGroup getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ConversationViewHolder holder;
            if (convertView == null) {
                holder = new ConversationViewHolder();
                LinearLayout card = new LinearLayout(activity);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(12), dp(10), dp(12), dp(10));
                card.setLayoutParams(new AbsListView.LayoutParams(
                        AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT));

                holder.card = card;

                // Row 1: Header (App badge + Title + Time)
                LinearLayout headRow = new LinearLayout(activity);
                headRow.setOrientation(LinearLayout.HORIZONTAL);
                headRow.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams headParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                headRow.setLayoutParams(headParams);

                holder.appBadge = new TextView(activity);
                holder.appBadge.setTextSize(9f);
                holder.appBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                holder.appBadge.setTextColor(getColor(R.color.deck_accent));
                holder.appBadge.setBackgroundResource(R.drawable.bg_cyber_button);
                holder.appBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
                headRow.addView(holder.appBadge);

                holder.title = new TextView(activity);
                holder.title.setTextSize(13f);
                holder.title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                holder.title.setTextColor(getColor(R.color.text_primary));
                holder.title.setSingleLine(true);
                holder.title.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                titleParams.leftMargin = dp(8);
                titleParams.rightMargin = dp(8);
                holder.title.setLayoutParams(titleParams);
                headRow.addView(holder.title);

                holder.time = new TextView(activity);
                holder.time.setTextSize(10f);
                holder.time.setTypeface(Typeface.MONOSPACE);
                holder.time.setTextColor(getColor(R.color.deck_text_dim));
                headRow.addView(holder.time);

                card.addView(headRow);

                // Row 2: Badges (Total, Unread, Flagged, Media)
                LinearLayout badgeRow = new LinearLayout(activity);
                badgeRow.setOrientation(LinearLayout.HORIZONTAL);
                badgeRow.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams badgeRowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                badgeRowParams.topMargin = dp(6);
                badgeRow.setLayoutParams(badgeRowParams);

                holder.totalBadge = new TextView(activity);
                holder.totalBadge.setTextSize(10f);
                holder.totalBadge.setTypeface(Typeface.MONOSPACE);
                holder.totalBadge.setTextColor(getColor(R.color.deck_text_dim));
                badgeRow.addView(holder.totalBadge);

                holder.unreadBadge = new TextView(activity);
                holder.unreadBadge.setTextSize(9f);
                holder.unreadBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                holder.unreadBadge.setTextColor(getColor(R.color.deck_bg));
                holder.unreadBadge.setBackgroundColor(getColor(R.color.deck_accent));
                holder.unreadBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
                LinearLayout.LayoutParams unreadParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                unreadParams.leftMargin = dp(8);
                holder.unreadBadge.setLayoutParams(unreadParams);
                badgeRow.addView(holder.unreadBadge);

                holder.flaggedBadge = new TextView(activity);
                holder.flaggedBadge.setTextSize(9f);
                holder.flaggedBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                holder.flaggedBadge.setTextColor(getColor(R.color.deck_error));
                holder.flaggedBadge.setBackgroundResource(R.drawable.bg_cyber_button);
                holder.flaggedBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
                LinearLayout.LayoutParams flaggedParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                flaggedParams.leftMargin = dp(8);
                holder.flaggedBadge.setLayoutParams(flaggedParams);
                badgeRow.addView(holder.flaggedBadge);

                holder.mediaBadges = new TextView(activity);
                holder.mediaBadges.setTextSize(10f);
                holder.mediaBadges.setTypeface(Typeface.MONOSPACE);
                holder.mediaBadges.setTextColor(getColor(R.color.deck_accent));
                LinearLayout.LayoutParams mediaParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                mediaParams.leftMargin = dp(8);
                holder.mediaBadges.setLayoutParams(mediaParams);
                badgeRow.addView(holder.mediaBadges);

                card.addView(badgeRow);

                // Row 3: Preview snippet
                holder.preview = new TextView(activity);
                holder.preview.setTextSize(12f);
                holder.preview.setTextColor(getColor(R.color.deck_text));
                holder.preview.setSingleLine(true);
                holder.preview.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                previewParams.topMargin = dp(6);
                holder.preview.setLayoutParams(previewParams);
                card.addView(holder.preview);

                card.setTag(holder);
                convertView = card;
            } else {
                holder = (ConversationViewHolder) convertView.getTag();
            }

            final ConversationGroup conv = getItem(position);
            bindConversationCard(holder, conv, root);
            return convertView;
        }
    }

    private void bindConversationCard(final ConversationViewHolder holder, final ConversationGroup conv, final View root) {
        holder.card.setBackgroundResource(conv.hasFlagged()
                ? R.drawable.bg_cyber_row_flagged : R.drawable.bg_cyber_row);

        holder.appBadge.setText(conv.displayApp().toUpperCase(java.util.Locale.US));

        String contact = ContactResolver.display(activity, conv.displayName());
        if (conv.isGroup()) {
            holder.title.setText("\uD83D\uDC65 " + contact);
        } else {
            holder.title.setText(contact);
        }

        if (conv.lastPostTime > 0) {
            holder.time.setText(DeletedMessageRules.formatTime(conv.lastPostTime, java.util.TimeZone.getDefault()));
            holder.time.setVisibility(View.VISIBLE);
        } else {
            holder.time.setVisibility(View.GONE);
        }

        holder.totalBadge.setText(conv.totalCount + (conv.totalCount == 1 ? " msg" : " msgs"));

        if (conv.hasUnread()) {
            holder.unreadBadge.setText(conv.unreadCount + " NEW");
            holder.unreadBadge.setVisibility(View.VISIBLE);
        } else {
            holder.unreadBadge.setVisibility(View.GONE);
        }

        if (conv.hasFlagged()) {
            holder.flaggedBadge.setText("\uD83D\uDEA9 " + conv.flaggedCount + " INCRIMINATING");
            holder.flaggedBadge.setVisibility(View.VISIBLE);
        } else {
            holder.flaggedBadge.setVisibility(View.GONE);
        }

        StringBuilder mediaSb = new StringBuilder();
        if (conv.photoCount > 0) mediaSb.append("\uD83D\uDCF7 ").append(conv.photoCount).append(" ");
        if (conv.videoCount > 0) mediaSb.append("\uD83C\uDFAC ").append(conv.videoCount).append(" ");
        if (conv.audioCount > 0) mediaSb.append("\uD83C\uDF99\uFE0F ").append(conv.audioCount).append(" ");
        if (conv.docCount > 0) mediaSb.append("\uD83D\uDCC4 ").append(conv.docCount);
        String mediaStr = mediaSb.toString().trim();
        if (!mediaStr.isEmpty()) {
            holder.mediaBadges.setText(mediaStr);
            holder.mediaBadges.setVisibility(View.VISIBLE);
        } else {
            holder.mediaBadges.setVisibility(View.GONE);
        }

        String previewText;
        if (conv.lastBody != null && !conv.lastBody.isEmpty()) {
            if (conv.isGroup() && conv.lastSender != null && !conv.lastSender.isEmpty()) {
                previewText = conv.lastSender + ": " + conv.lastBody;
            } else {
                previewText = conv.lastBody;
            }
        } else {
            previewText = "(media attachment)";
        }
        holder.preview.setText(previewText);

        holder.card.setOnClickListener(v -> {
            ConversationReadTracker.markRead(activity, conv.pkg, conv.threadTitle);
            openConversationThread(conv, root);
        });

        holder.card.setOnLongClickListener(v -> {
            java.util.List<String> options = new java.util.ArrayList<>();
            options.add("\uD83D\uDCAC Open conversation");
            options.add("\u2713 Mark as read");
            new AlertDialog.Builder(activity)
                    .setTitle(ContactResolver.display(activity, conv.displayName()))
                    .setItems(options.toArray(new String[0]), (d, which) -> {
                        if (which == 0) {
                            ConversationReadTracker.markRead(activity, conv.pkg, conv.threadTitle);
                            openConversationThread(conv, root);
                        } else if (which == 1) {
                            ConversationReadTracker.markRead(activity, conv.pkg, conv.threadTitle);
                            renderArchive(root);
                        }
                    })
                    .show();
            return true;
        });
    }


    private boolean isArchiveFilterActive(NotificationArchive.Filter filter) {
        if (filter == null) return false;
        if (filter.search != null && !filter.search.trim().isEmpty()) return true;
        if (filter.quickFilter != NotificationArchive.Filter.QUICK_ALL) return true;
        if (filter.fromMs > 0 || filter.toMs > 0) return true;
        if (filter.conversationThread != null && !filter.conversationThread.isEmpty()) return true;
        return false;
    }

    /**
     * Export the archive. Supports full ZIP backup (with media), standalone HTML forensic report,
     * CSV spreadsheet, and plain text transcript, with selective filtering support.
     */
    private void exportArchive() {
        exportArchive(rootView != null ? rootView.findViewById(R.id.archiveView) : null);
    }

    private void exportArchive(View archiveRoot) {
        if (NotificationArchive.totalMessages(activity) == 0) {
            toast("Nothing to export yet.");
            return;
        }

        View root = archiveRoot != null ? archiveRoot : rootView != null ? rootView.findViewById(R.id.archiveView) : null;
        final NotificationArchive.Filter activeFilter = root != null ? buildArchiveFilter(root) : null;
        boolean hasFilter = isArchiveFilterActive(activeFilter);

        if (hasFilter) {
            new AlertDialog.Builder(activity)
                    .setTitle("Export Archive")
                    .setMessage("A filter or search query is currently active in your vault.\n\n"
                            + "Would you like to export only the currently filtered entries, or the entire database?")
                    .setNeutralButton("Cancel", null)
                    .setNegativeButton("Entire Archive", (d, w) -> {
                        pendingExportFilter = null;
                        showExportFormatPicker(false);
                    })
                    .setPositiveButton("Filtered View", (d, w) -> {
                        pendingExportFilter = activeFilter;
                        showExportFormatPicker(true);
                    })
                    .show();
        } else {
            pendingExportFilter = null;
            showExportFormatPicker(false);
        }
    }

    private void showExportFormatPicker(final boolean isFiltered) {
        String[] options = new String[] {
                "\uD83D\uDCE6 Full ZIP Backup (with photos & media)",
                "\uD83C\uDF10 Standalone HTML Forensic Report",
                "\uD83D\uDCCA CSV Spreadsheet",
                "\uD83D\uDCC4 Plain Text Transcript"
        };

        new AlertDialog.Builder(activity)
                .setTitle(isFiltered ? "Export Filtered Archive" : "Export Archive")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            startArchiveExport(EXPORT_ARCHIVE_ZIP, "application/zip", "zip", isFiltered);
                            break;
                        case 1:
                            startArchiveExport(EXPORT_ARCHIVE_HTML, "text/html", "html", isFiltered);
                            break;
                        case 2:
                            startArchiveExport(EXPORT_ARCHIVE_CSV, "text/csv", "csv", isFiltered);
                            break;
                        case 3:
                            startArchiveExport(EXPORT_ARCHIVE_TEXT, "text/plain", "txt", isFiltered);
                            break;
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startArchiveExport(int requestCode, String mimeType, String extension, boolean isFiltered) {
        try {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mimeType)
                    .putExtra(Intent.EXTRA_TITLE, ArchiveExport.fileName(
                            extension, isFiltered, System.currentTimeMillis(), java.util.TimeZone.getDefault())),
                    requestCode);
        } catch (Exception e) {
            toast("No file picker available on this phone.");
        }
    }

}
