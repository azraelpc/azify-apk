package org.azify.client;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.Scheduler;

import java.util.List;

@UnstableApi
public final class MusicDownloadService extends DownloadService {
    private static final String CHANNEL = "azify-downloads";

    public MusicDownloadService() { super(2, 1_000, CHANNEL, R.string.downloads, 0); }

    @Override protected DownloadManager getDownloadManager() { return Downloads.get(this).manager(); }
    @Nullable @Override protected Scheduler getScheduler() { return null; }

    @Override protected Notification getForegroundNotification(List<Download> downloads, int notMetRequirements) {
        int count = 0;
        float total = 0;
        for (Download download : downloads) {
            if (download.state == Download.STATE_DOWNLOADING) {
                count++;
                total += Math.max(0, download.getPercentDownloaded());
            }
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getService(this, 1,
                DownloadService.buildPauseDownloadsIntent(this, MusicDownloadService.class, false),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(getString(R.string.downloads))
                .setContentText(getString(notMetRequirements == 0 ? R.string.downloading : R.string.download_waiting))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(100, count == 0 ? 0 : Math.round(total / count), count == 0)
                .addAction(android.R.drawable.ic_media_pause, getString(R.string.pause_downloads), pause).build();
    }
}