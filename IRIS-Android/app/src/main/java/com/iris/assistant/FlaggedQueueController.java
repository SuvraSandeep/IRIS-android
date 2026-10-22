package com.iris.assistant;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Controller for the Flagged & Pinned Queue and Reminders section in Comms Deck.
 */
public class FlaggedQueueController {

    public interface OnPick {
        void pick(int which);
    }

    private MainActivity activity;
    private View rootView;

    /** Filter for FLAGGED section: 0 ALL, 1 PINNED, 2 FLAGGED, 3 REMINDERS */
    private int flaggedFilter = 0;
    private String flaggedSearch = "";

    public void bind(MainActivity activity, View view) {
        this.activity = activity;
        this.rootView = view;
        bindFlaggedSection(view);
    }

    private int dp(int value) {
        if (activity == null) return value;
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        if (activity != null) activity.toast(message);
    }

    public void bindFlaggedSection(final View view) {
        final Button btnAll = view.findViewById(R.id.flaggedFilterAll);
        final Button btnPinned = view.findViewById(R.id.flaggedFilterPinned);
        final Button btnFlagged = view.findViewById(R.id.flaggedFilterFlagged);
        final Button btnReminders = view.findViewById(R.id.flaggedFilterReminders);

        View.OnClickListener filterListener = v -> {
            if (v == btnAll) flaggedFilter = 0;
            else if (v == btnPinned) flaggedFilter = 1;
            else if (v == btnFlagged) flaggedFilter = 2;
            else if (v == btnReminders) flaggedFilter = 3;
            updateFlaggedFilterButtons(view);
            renderFlaggedItems(view);
        };

        if (btnAll != null) btnAll.setOnClickListener(filterListener);
        if (btnPinned != null) btnPinned.setOnClickListener(filterListener);
        if (btnFlagged != null) btnFlagged.setOnClickListener(filterListener);
        if (btnReminders != null) btnReminders.setOnClickListener(filterListener);

        EditText search = view.findViewById(R.id.flaggedSearch);
        if (search != null) {
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    flaggedSearch = s == null ? "" : s.toString().trim();
                    renderFlaggedItems(view);
                }
                @Override public void afterTextChanged(Editable s) { }
            });
        }

        View refreshBtn = view.findViewById(R.id.flaggedRefreshButton);
        if (refreshBtn != null) {
            refreshBtn.setOnClickListener(v -> {
                renderFlaggedItems(view);
                toast("Queue refreshed.");
            });
        }

        View summary = view.findViewById(R.id.flaggedSummary);
        if (summary != null) {
            summary.setOnClickListener(v -> {
                renderFlaggedItems(view);
                toast("Queue refreshed.");
            });
        }

        View addBtn = view.findViewById(R.id.flaggedAddButton);
        if (addBtn != null) addBtn.setOnClickListener(v -> showAddFlaggedItemDialog(view));

        View clearBtn = view.findViewById(R.id.flaggedClearCompletedButton);
        if (clearBtn != null) clearBtn.setOnClickListener(v -> {
            new AlertDialog.Builder(activity)
                    .setTitle("Clear completed items?")
                    .setMessage("This will remove all completed pins, flags, and fired reminders.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear", (d, w) -> {
                        FlaggedStore.get(activity).clearCompleted();
                        ReminderStore store = new ReminderStore(activity);
                        for (ReminderStore.Reminder r : store.all()) {
                            if (r.fired) {
                                store.remove(r.id);
                                ReminderStore.cancel(activity, r.id);
                            }
                        }
                        renderFlaggedItems(view);
                    })
                    .show();
        });

        updateFlaggedFilterButtons(view);
        renderFlaggedItems(view);
    }

    private void updateFlaggedFilterButtons(View root) {
        int[] btnIds = {R.id.flaggedFilterAll, R.id.flaggedFilterPinned,
                R.id.flaggedFilterFlagged, R.id.flaggedFilterReminders};
        for (int i = 0; i < btnIds.length; i++) {
            Button b = root.findViewById(btnIds[i]);
            if (b == null) continue;
            boolean active = i == flaggedFilter;
            b.setTextColor(activity.getColor(active ? R.color.magenta : R.color.deck_inactive));
            b.setTypeface(Typeface.MONOSPACE,
                    active ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    public static class DisplayQueueItem {
        long id;
        String kind = "";
        String text = "";
        String sender = "";
        String appLabel = "";
        String reason = "";
        long createdMs;
        long dueMs;
        boolean completed;
        Object rawRef;
    }

    public void renderFlaggedItems(final View root) {
        TextView summary = root.findViewById(R.id.flaggedSummary);
        int numPinned = FlaggedStore.get(activity).totalPinned();
        int numFlagged = FlaggedStore.get(activity).totalFlagged();
        int numReminders = new ReminderStore(activity).all().size();
        if (summary != null) {
            summary.setText(numPinned + " PINNED · " + numFlagged + " FLAGGED · "
                    + numReminders + " REMINDERS");
        }

        LinearLayout host = root.findViewById(R.id.flaggedListHost);
        if (host == null) return;
        host.removeAllViews();

        List<DisplayQueueItem> list = new ArrayList<>();
        String query = flaggedSearch.toLowerCase(Locale.ROOT);

        for (FlaggedStore.Item it : FlaggedStore.get(activity).all()) {
            if (flaggedFilter == 1 && !it.isPinned()) continue;
            if (flaggedFilter == 2 && !it.isFlagged()) continue;
            if (flaggedFilter == 3) continue;

            if (!query.isEmpty()) {
                String full = (it.text + " " + it.sender + " " + it.appLabel + " " + it.reason)
                        .toLowerCase(Locale.ROOT);
                if (!full.contains(query)) continue;
            }

            DisplayQueueItem d = new DisplayQueueItem();
            d.id = it.id;
            d.kind = it.kind;
            d.text = it.text;
            d.sender = it.sender;
            d.appLabel = it.appLabel;
            d.reason = it.reason;
            d.createdMs = it.createdMs;
            d.dueMs = 0;
            d.completed = it.completed;
            d.rawRef = it;
            list.add(d);
        }

        if (flaggedFilter == 0 || flaggedFilter == 3) {
            for (ReminderStore.Reminder r : new ReminderStore(activity).all()) {
                if (!query.isEmpty()) {
                    String full = (r.task + " " + r.sender).toLowerCase(Locale.ROOT);
                    if (!full.contains(query)) continue;
                }

                DisplayQueueItem d = new DisplayQueueItem();
                d.id = r.id;
                d.kind = "REMINDER";
                d.text = r.task.isEmpty() ? "(reminder)" : r.task;
                d.sender = r.sender;
                d.appLabel = "IRIS";
                d.reason = r.undated() ? "Undated reminder" : "Scheduled alarm";
                d.createdMs = r.createdMs;
                d.dueMs = r.dueMs;
                d.completed = r.fired;
                d.rawRef = r;
                list.add(d);
            }
        }

        Collections.sort(list, (a, b) -> {
            if (a.completed != b.completed) return a.completed ? 1 : -1;
            if (a.dueMs > 0 && b.dueMs > 0) return Long.compare(a.dueMs, b.dueMs);
            return Long.compare(b.createdMs, a.createdMs);
        });

        if (list.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("No pinned messages, flagged pings, or reminders matching current filters.");
            empty.setTextSize(11f);
            empty.setTypeface(Typeface.MONOSPACE);
            empty.setTextColor(activity.getColor(R.color.deck_text_dim));
            empty.setPadding(0, dp(16), 0, dp(16));
            host.addView(empty);
            return;
        }

        for (final DisplayQueueItem item : list) {
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_cyber_panel);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            card.setLayoutParams(lp);

            LinearLayout topRow = new LinearLayout(activity);
            topRow.setOrientation(LinearLayout.HORIZONTAL);

            TextView badge = new TextView(activity);
            badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            badge.setTextSize(10f);
            if (item.completed) {
                badge.setText("[✓ DONE]");
                badge.setTextColor(activity.getColor(R.color.deck_inactive));
            } else if ("PINNED".equalsIgnoreCase(item.kind)) {
                badge.setText("[📌 PINNED]");
                badge.setTextColor(activity.getColor(R.color.deck_accent));
            } else if ("FLAGGED".equalsIgnoreCase(item.kind)) {
                badge.setText("[🚩 FLAGGED]");
                badge.setTextColor(activity.getColor(R.color.danger));
            } else {
                badge.setText("[⏰ REMINDER]");
                badge.setTextColor(activity.getColor(R.color.magenta));
            }
            topRow.addView(badge);

            View spacer = new View(activity);
            LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(0, 1, 1f);
            spacer.setLayoutParams(spacerLp);
            topRow.addView(spacer);

            TextView timeTv = new TextView(activity);
            timeTv.setTypeface(Typeface.MONOSPACE);
            timeTv.setTextSize(10f);
            timeTv.setTextColor(activity.getColor(R.color.deck_text_dim));
            if (item.dueMs > 0) {
                timeTv.setText("Due: " + DeletedMessageRules.formatTime(item.dueMs, TimeZone.getDefault()));
            } else {
                timeTv.setText(DeletedMessageRules.formatTime(item.createdMs, TimeZone.getDefault()));
            }
            topRow.addView(timeTv);
            card.addView(topRow);

            TextView bodyTv = new TextView(activity);
            bodyTv.setText(item.text);
            bodyTv.setTextSize(13f);
            bodyTv.setTypeface(Typeface.MONOSPACE,
                    item.completed ? Typeface.NORMAL : Typeface.BOLD);
            bodyTv.setTextColor(activity.getColor(item.completed ? R.color.deck_inactive : R.color.text_primary));
            bodyTv.setPadding(0, dp(6), 0, dp(4));
            bodyTv.setTextIsSelectable(true);
            card.addView(bodyTv);

            String who = (item.sender != null && !item.sender.isEmpty()) ? item.sender : "Self";
            String app = (item.appLabel != null && !item.appLabel.isEmpty()) ? item.appLabel : "IRIS";
            String meta = who + "  ·  " + app;
            if (item.reason != null && !item.reason.isEmpty()) {
                meta += "  ·  " + item.reason;
            }
            TextView metaTv = new TextView(activity);
            metaTv.setText(meta);
            metaTv.setTextSize(10f);
            metaTv.setTypeface(Typeface.MONOSPACE);
            metaTv.setTextColor(activity.getColor(R.color.deck_text_dim));
            card.addView(metaTv);

            card.setOnClickListener(v -> showQueueItemDetailDialog(item, root));
            card.setOnLongClickListener(v -> {
                showQueueItemDeleteDialog(item, root);
                return true;
            });

            host.addView(card);
        }
    }

    private void showQueueItemDetailDialog(final DisplayQueueItem item, final View root) {
        String title = "PINNED".equalsIgnoreCase(item.kind) ? "📌 Pinned Message"
                : "FLAGGED".equalsIgnoreCase(item.kind) ? "🚩 Flagged Message" : "⏰ Scheduled Reminder";

        StringBuilder sb = new StringBuilder();
        sb.append(item.text).append("\n\n");
        sb.append("Sender: ").append(item.sender.isEmpty() ? "Self" : item.sender).append("\n");
        sb.append("App: ").append(item.appLabel.isEmpty() ? "IRIS" : item.appLabel).append("\n");
        if (item.dueMs > 0) {
            sb.append("Due: ").append(DeletedMessageRules.formatTime(item.dueMs, TimeZone.getDefault())).append("\n");
        }
        sb.append("Created: ").append(DeletedMessageRules.formatTime(item.createdMs, TimeZone.getDefault())).append("\n");
        if (item.reason != null && !item.reason.isEmpty()) {
            sb.append("Context: ").append(item.reason).append("\n");
        }
        sb.append("Status: ").append(item.completed ? "Completed / Fired" : "Active / Pending");

        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(sb.toString())
                .setPositiveButton("Close", null)
                .setNeutralButton(item.completed ? "Mark Active" : "Mark Done", (d, w) -> {
                    if (item.rawRef instanceof FlaggedStore.Item) {
                        FlaggedStore.get(activity).setCompleted(item.id, !item.completed);
                    } else if (item.rawRef instanceof ReminderStore.Reminder) {
                        ReminderStore store = new ReminderStore(activity);
                        if (item.completed) {
                            ReminderStore.Reminder r = (ReminderStore.Reminder) item.rawRef;
                            r.fired = false;
                            store.remove(r.id);
                            store.add(r.task, r.sender, r.dueMs, r.id);
                        } else {
                            store.markFired(item.id);
                        }
                    }
                    renderFlaggedItems(root);
                })
                .setNegativeButton("Delete", (d, w) -> {
                    deleteQueueItem(item);
                    renderFlaggedItems(root);
                })
                .show();
    }

    private void showQueueItemDeleteDialog(final DisplayQueueItem item, final View root) {
        new AlertDialog.Builder(activity)
                .setTitle("Delete this item?")
                .setMessage("“" + item.text + "”")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Delete", (d, w) -> {
                    deleteQueueItem(item);
                    renderFlaggedItems(root);
                })
                .show();
    }

    private void deleteQueueItem(DisplayQueueItem item) {
        if (item.rawRef instanceof FlaggedStore.Item) {
            FlaggedStore.get(activity).remove(item.id);
        } else if (item.rawRef instanceof ReminderStore.Reminder) {
            ReminderStore store = new ReminderStore(activity);
            store.remove(item.id);
            ReminderStore.cancel(activity, item.id);
        }
    }

    private void choiceDialog(String title, String message, String[] labels, OnPick onPick) {
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setItems(labels, (d, which) -> onPick.pick(which))
                .setNegativeButton("Cancel", null)
                .show();
    }

    public void showAddFlaggedItemDialog(final View root) {
        final String[] types = {"📌 Pinned Note", "🚩 Urgent Flag", "⏰ Reminder"};
        choiceDialog("+ New Item", "Choose what kind of item to add:", types, which -> {
            final boolean isReminder = which == 2;
            final boolean isPinned = which == 0;

            final LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(16), dp(10), dp(16), dp(6));

            final EditText textInput = new EditText(activity);
            textInput.setHint(isReminder ? "Remind me to..." : isPinned ? "Note to pin..." : "Urgent matter to flag...");
            textInput.setMaxLines(3);
            box.addView(textInput);

            final EditText timeInput = new EditText(activity);
            if (isReminder) {
                timeInput.setHint("When? (e.g. 10m, 1h, 18:00, or blank for undated)");
                timeInput.setMaxLines(1);
                box.addView(timeInput);
            }

            new AlertDialog.Builder(activity)
                    .setTitle(types[which])
                    .setView(box)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Save", (d, w) -> {
                        String text = textInput.getText().toString().trim();
                        if (text.isEmpty()) return;
                        long now = System.currentTimeMillis();
                        if (isReminder) {
                            String whenStr = timeInput.getText().toString().trim();
                            long due = 0;
                            if (!whenStr.isEmpty()) {
                                Calendar cal = Calendar.getInstance();
                                cal.setTimeInMillis(now);
                                int minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
                                int nowWeekday = cal.get(Calendar.DAY_OF_WEEK);
                                int nowDay = cal.get(Calendar.DAY_OF_MONTH);
                                int nowMonth = cal.get(Calendar.MONTH) + 1;
                                int nowYear = cal.get(Calendar.YEAR);

                                String cleanWhen = whenStr;
                                if (cleanWhen.matches("(?i)^\\d+\\s*m$")) {
                                    cleanWhen = cleanWhen.replaceFirst("(?i)m$", " mins");
                                } else if (cleanWhen.matches("(?i)^\\d+\\s*h$")) {
                                    cleanWhen = cleanWhen.replaceFirst("(?i)h$", " hours");
                                }

                                ReminderParser.Result parsed = ReminderParser.parse(cleanWhen);
                                int delay = ReminderParser.delayMinutes(parsed, minuteOfDay, nowWeekday, nowDay, nowMonth, nowYear);
                                if (delay <= 0) {
                                    delay = ReminderParser.delayMinutes(
                                            ReminderParser.parse("in " + cleanWhen),
                                            minuteOfDay, nowWeekday, nowDay, nowMonth, nowYear);
                                }
                                if (delay <= 0) {
                                    delay = ReminderParser.delayMinutes(
                                            ReminderParser.parse("remind me " + cleanWhen),
                                            minuteOfDay, nowWeekday, nowDay, nowMonth, nowYear);
                                }
                                if (delay > 0) {
                                    due = now + delay * 60_000L;
                                }
                            }
                            ReminderStore store = new ReminderStore(activity);
                            long id = store.add(text, "Owner", due, now);
                            if (due > 0) {
                                ReminderStore.schedule(activity, id, text, due);
                            }
                        } else if (isPinned) {
                            FlaggedStore.get(activity).addPinned(text, "Owner", "IRIS", "Manual note", now);
                        } else {
                            FlaggedStore.get(activity).addFlagged(text, "Owner", "IRIS", "Manual urgent flag", now);
                        }
                        renderFlaggedItems(root);
                    })
                    .show();
        });
    }
}
