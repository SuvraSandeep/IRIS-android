package com.iris.assistant;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.os.Build;

public class IrisTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (IrisListeningService.isRunning) {
            startService(new Intent(this, IrisListeningService.class).setAction(IrisListeningService.ACTION_STOP));
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                || (AppSettings.MODE_WAKE.equals(new AppSettings(this).listeningMode())
                && !new ProfileStore(this).getWakeProfile().isReady())) {
            Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, open,
                        android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT));
            } else {
                startActivityAndCollapse(open);
            }
        } else {
            startForegroundService(new Intent(this, IrisListeningService.class).setAction(IrisListeningService.ACTION_START));
        }
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(IrisListeningService.isRunning ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.updateTile();
        }
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setLabel("IRIS");
        if (Build.VERSION.SDK_INT >= 29)
            tile.setSubtitle(IrisListeningService.isRunning ? "Listening" : "Tap to wake");
        tile.setState(IrisListeningService.isRunning ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
