package com.iris.assistant;

import android.app.SearchManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Official Android intents only. No network client, arbitrary URI or accessibility clicks. */
public final class AppIntegrations {
    private AppIntegrations() { }
    public static final class Prepared {
        public final Intent intent;
        public final String message;
        Prepared(Intent intent, String message) { this.intent = intent; this.message = message; }
    }
    public static Prepared prepare(Context context, AppRequest r) {
        if (r == null || !r.valid()) return new Prepared(null, "That app action is not supported.");
        String pkg = AppRequest.packageFor(r.app);
        try {
            context.getPackageManager().getApplicationInfo(pkg, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return new Prepared(null, r.app + " is not installed. No other app was selected.");
        }
        Intent target;
        String message;
        if (r.action.equals("search")) {
            if (pkg.equals("com.google.android.apps.maps")) {
                target = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(r.value)));
            } else if (pkg.equals("com.android.chrome")) {
                target = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(r.value)));
            } else {
                target = new Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, r.value);
            }
            message = "Opening a search in " + r.app + " for " + r.value + ".";
        } else if (r.action.equals("share_text")) {
            target = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r.value);
            message = "Opening " + r.app + " with your text. Choose the recipient and confirm Send there.";
        } else {
            RecentMedia.Kind kind = RecentMedia.kindFrom(r.value);
            RecentMedia.Item item = RecentMedia.newest(context, kind);
            if (item == null) return new Prepared(null, "No accessible " + r.value + " was found. Nothing was shared.");
            if (!"content".equals(item.uri.getScheme())) return new Prepared(null, "This file cannot be shared safely.");
            target = new Intent(Intent.ACTION_SEND).setType(item.mime)
                    .putExtra(Intent.EXTRA_STREAM, item.uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            target.setClipData(ClipData.newRawUri(item.name, item.uri));
            message = "Opening " + r.app + " with " + item.name + ". Review the file and recipient, then confirm Send there.";
        }
        target.setPackage(pkg);
        if (target.resolveActivity(context.getPackageManager()) == null) {
            return new Prepared(null, r.app + " does not expose this action on this phone. Open the app and complete it there.");
        }
        if (!r.action.equals("search")) target = Intent.createChooser(target, "Review in " + r.app);
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return new Prepared(target, message);
    }
}
