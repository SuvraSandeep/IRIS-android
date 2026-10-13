package com.iris.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.Notification;
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
    /**
     * Manual send.
     *
     * The unlock check stays here rather than moving into NotificationReply: it exists so that a
     * model able to open this screen still cannot press Send. The auto-responder is authorised a
     * different way and must work while the phone is locked, so sharing this rule would be wrong
     * for one caller or the other.
     *
     * The verification and the submission itself now live in NotificationReply, shared with the
     * responder, so there is only one copy of the guards to keep correct.
     */
    private void send(StatusBarNotification selected, String body) {
        if (!unlocked()) { finish(); return; }

        NotificationReply.Result result =
                NotificationReply.send(this, selected, body, "notification_reply");
        if (NotificationReply.sent(result)) {
            android.widget.Toast.makeText(this, NotificationReply.explain(result),
                    android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(NotificationReply.explain(result) + " Select it again.")
                .setPositiveButton("Refresh", (d, w) -> showReplies()).show();
    }
}
