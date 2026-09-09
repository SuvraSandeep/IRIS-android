package com.iris.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Explicit, unlocked review UI. A model can open this screen but cannot press Send. */
public final class NotificationReplyActivity extends Activity {
    private boolean unlocked() {
        KeyguardManager k = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return k != null && !k.isKeyguardLocked();
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }
    @Override protected void onResume() {
        super.onResume();
        if (!unlocked()) { finish(); return; }
        showReplies();
    }
    private void showReplies() {
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        rows.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText("Reply to a notification\nChoose a conversation. You'll review the reply before sending.");
        title.setTextSize(20);
        rows.addView(title);
        int count = 0;
        for (StatusBarNotification item : IrisNotificationListener.activeForReply()) {
            if (IrisNotificationListener.replyAction(item) == null || item.getPackageName().equals(getPackageName())) continue;
            String label = item.getPackageName();
            try { label = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(label, 0)).toString(); }
            catch (Exception ignored) { }
            final String who = label + " — " + item.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE, "Conversation");
            Button choose = new Button(this);
            choose.setText(who);
            choose.setOnClickListener(v -> compose(item, who));
            rows.addView(choose);
            count++;
        }
        if (count == 0) {
            TextView empty = new TextView(this);
            empty.setText("No active notifications with a supported Reply action. Enable IRIS notification access if needed. Some apps do not support replies here.");
            rows.addView(empty);
            Button settings = new Button(this);
            settings.setText("Notification access settings");
            settings.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
            rows.addView(settings);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        setContentView(scroll);
    }
    private void compose(StatusBarNotification selected, String who) {
        EditText input = new EditText(this);
        input.setHint("Type your exact reply");
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});
        new AlertDialog.Builder(this).setTitle(who).setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Review", (d, w) -> {
                    String body = input.getText().toString();
                    if (body.trim().isEmpty()) return;
                    new AlertDialog.Builder(this).setTitle("Send to " + who + "?")
                            .setMessage(body).setNegativeButton("Cancel", null)
                            .setPositiveButton("Send reply", (dialog, which) -> send(selected, body)).show();
                }).show();
    }
    private void send(StatusBarNotification selected, String body) {
        if (!unlocked()) { finish(); return; }
        for (StatusBarNotification current : IrisNotificationListener.activeForReply()) {
            if (!current.getKey().equals(selected.getKey())) continue;
            Notification.Action action = IrisNotificationListener.replyAction(current);
            if (action == null || current.getPostTime() != selected.getPostTime()
                    || !action.actionIntent.equals(IrisNotificationListener.replyAction(selected).actionIntent)) break;
            try {
                RemoteInput field = action.getRemoteInputs()[0];
                Bundle result = new Bundle();
                result.putCharSequence(field.getResultKey(), body);
                Intent intent = new Intent();
                RemoteInput.addResultsToIntent(action.getRemoteInputs(), intent, result);
                action.actionIntent.send(this, 0, intent);
                try (ActionLedger ledger = new ActionLedger(this)) {
                    ledger.record("notification_reply", ActionLedger.OK,
                            "Reply submitted to " + current.getPackageName() + "; delivery unverified", "", "", false);
                }
                android.widget.Toast.makeText(this, "Reply submitted to the app. Delivery is not verified.", android.widget.Toast.LENGTH_LONG).show();
                finish();
                return;
            } catch (Exception ignored) { break; }
        }
        new AlertDialog.Builder(this).setMessage("The notification changed or the reply action expired. Select it again.")
                .setPositiveButton("Refresh", (d, w) -> showReplies()).show();
    }
}
