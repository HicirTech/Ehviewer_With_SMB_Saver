package com.hippo.ehviewer.smb;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.util.IoThreadPoolExecutor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Coordinates "Save to SMB" enqueues from both the gallery reader (auto) and the gallery
 * detail screen (manual).
 * <p>
 * The auto path runs only when both {@link Settings#getSmbSaveEnabled()} and
 * {@link Settings#getSmbAutoDownloadEnabled()} are true. The manual path only requires
 * the master save switch (so the user can opt-in per gallery from the Download button).
 * <p>
 * Common behaviour:
 * <ol>
 *   <li>Skip galleries whose on-share copy already has all images present
 *       ({@link SmbStorage#isGalleryComplete}).</li>
 *   <li>Write a skeleton {@code metadata.json} immediately so Local Inventory lists the
 *       gallery before/even without a finished download.</li>
 *   <li>Hand the gallery off to {@link SmbDirectDownloader} for the actual download.</li>
 * </ol>
 */
public final class SmbAutoDownloadManager {

    private static final String TAG = "SmbAutoDownloadMgr";
    private static final SmbAutoDownloadManager INSTANCE = new SmbAutoDownloadManager();

    private final Set<Long> pendingGids = Collections.synchronizedSet(new HashSet<>());

    private SmbAutoDownloadManager() {}

    public static SmbAutoDownloadManager getInstance() {
        return INSTANCE;
    }

    /**
     * Clear the per-process dedup mark for a gid so it can be re-enqueued. Called by
     * {@link SmbDirectDownloader} on cancel and on natural finish — without this, a gid
     * stays "pending" forever in our in-memory set and any subsequent manual/auto
     * enqueue silently no-ops until the app is restarted.
     */
    public void clearPending(long gid) {
        pendingGids.remove(gid);
    }

    /** Called from the reader on first page open. Auto-download must be explicitly enabled. */
    public void enqueueFromFirstPage(@NonNull Context context, @NonNull GalleryInfo galleryInfo) {
        if (!Settings.getSmbSaveEnabled() || !Settings.getSmbAutoDownloadEnabled()
                || !SmbStorage.isConfigured()) {
            return;
        }
        enqueueInternal(context, galleryInfo);
    }

    /** Called from the detail screen "Save to SMB" choice. Bypasses the auto-download toggle. */
    public void enqueueManual(@NonNull Context context, @NonNull GalleryInfo galleryInfo) {
        if (!Settings.getSmbSaveEnabled() || !SmbStorage.isConfigured()) {
            Toast.makeText(context.getApplicationContext(),
                    R.string.smb_save_not_configured, Toast.LENGTH_SHORT).show();
            return;
        }
        enqueueInternal(context, galleryInfo);
    }

    private void enqueueInternal(@NonNull Context context, @NonNull GalleryInfo galleryInfo) {
        if (!pendingGids.add(galleryInfo.gid)) {
            return;
        }

        final Context appContext = context.getApplicationContext();
        // Auto-download path is fired from GalleryView.render() (render thread) and the
        // manual path is fired from a dialog click (main thread). Always post the toast
        // through SimpleHandler so we never call Toast.makeText().show() off the main
        // thread, which throws "Can't toast on a thread that has not called
        // Looper.prepare()".
        SimpleHandler.getInstance().post(() -> Toast.makeText(appContext,
                appContext.getString(R.string.smb_save_started,
                        galleryInfo.title != null ? galleryInfo.title
                                : ("gid " + galleryInfo.gid)),
                Toast.LENGTH_SHORT).show());

        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                if (SmbStorage.isGalleryComplete(galleryInfo)) {
                    pendingGids.remove(galleryInfo.gid);
                    SimpleHandler.getInstance().post(() -> Toast.makeText(appContext,
                            R.string.smb_save_already_complete, Toast.LENGTH_SHORT).show());
                    return;
                }
                try {
                    SmbMetadata.writeMetadataSkeleton(galleryInfo);
                } catch (Throwable e) {
                    Log.w(TAG, "Failed to write skeleton metadata gid=" + galleryInfo.gid, e);
                }
                SimpleHandler.getInstance().post(() ->
                        SmbDirectDownloader.getInstance().start(appContext, galleryInfo));
            } catch (Throwable e) {
                pendingGids.remove(galleryInfo.gid);
                Log.e(TAG, "enqueueInternal failed gid=" + galleryInfo.gid, e);
            }
        });
    }
}
