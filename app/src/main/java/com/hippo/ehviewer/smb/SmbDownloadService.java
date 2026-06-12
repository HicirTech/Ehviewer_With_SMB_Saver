package com.hippo.ehviewer.smb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.hippo.ehviewer.R;

/**
 * Foreground service that keeps the process alive while {@link SmbDirectDownloader} has work to
 * do (active downloads or queued galleries). Owns nothing itself — all state lives in the
 * singleton downloader. The downloader starts this service when the queue becomes non-empty and
 * stops it once everything is idle.
 */
public final class SmbDownloadService extends Service {

    private static final String TAG = "SmbDownloadService";
    // Bumped from "smb_download" to force a channel re-create on devices that had the
    // previous IMPORTANCE_LOW channel cached (the old channel hid the progress bar in
    // OEM "silent" notification groups).
    private static final String CHANNEL_ID = "smb_download_v2";
    private static final int NOTIFICATION_ID = 0x536D6244; // 'SmbD'

    public static final String ACTION_STOP = "com.hippo.ehviewer.smb.STOP";

    public static void start(Context context) {
        Intent intent = new Intent(context, SmbDownloadService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, SmbDownloadService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (IllegalStateException ignore) {
            // Service already stopped or cannot start in background — fine.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        startInForeground(buildNotification(getString(R.string.smb_download_notif_title),
                getString(R.string.smb_download_notif_preparing), 0, 0, true));
        SmbDirectDownloader.getInstance().attachService(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        // Make sure the foreground notification is up even on re-start.
        startInForeground(buildNotification(getString(R.string.smb_download_notif_title),
                getString(R.string.smb_download_notif_preparing), 0, 0, true));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        SmbDirectDownloader.getInstance().detachService();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void updateNotification(String title, String text, int max, int progress, boolean indeterminate) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification(title, text, max, progress, indeterminate));
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to update SMB notification", e);
        }
    }

    private void startInForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
    }

    private Notification buildNotification(String title, String text, int max, int progress, boolean indeterminate) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }
        // Always render a progress bar — phone DownloadService does the same, and many
        // OEM shades hide notifications without one in the "silent / minimised" group.
        if (max > 0) {
            b.setProgress(max, progress, indeterminate);
            b.setContentInfo(progress + "/" + max);
        } else {
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.smb_download_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setSound(null, null);
                channel.enableVibration(false);
                nm.createNotificationChannel(channel);
            }
        }
    }
}
