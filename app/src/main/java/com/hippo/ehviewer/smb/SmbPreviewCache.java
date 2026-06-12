package com.hippo.ehviewer.smb;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.data.GalleryInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jcifs.smb.SmbFile;

/**
 * Parallelises SMB preview reads. Conaco's disk-load executor is a single-thread serial
 * pool, so all per-cell loads happen one after another and a 40-page gallery on a slow
 * share appears to load previews "one at a time".
 *
 * <p>This cache prefetches every page in a gallery in parallel and writes each one to a
 * deterministic local file. {@link SmbImageDataContainer#get()} then hits the local file
 * directly without doing any SMB I/O on Conaco's serial thread, so the visible loading
 * becomes effectively concurrent.
 *
 * <p>Bandwidth assumption: the local SMB share is treated as having effectively unlimited
 * bandwidth, so we fan out a fixed worker pool ({@link #PREFETCH_PARALLELISM}) per gallery
 * rather than per page. The cache directory is shared with the legacy on-demand temp
 * staging path so disk space is bounded by the same eviction (manual clear / cache wipe).
 */
public final class SmbPreviewCache {

    private static final String TAG = "SmbPreviewCache";
    private static final String CACHE_SUBDIR = "smb_preview";
    private static final int PREFETCH_PARALLELISM = 6;

    private static final ExecutorService PREFETCH_EXECUTOR = new ThreadPoolExecutor(
            PREFETCH_PARALLELISM, PREFETCH_PARALLELISM,
            10L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, "smb-preview-prefetch");
                t.setDaemon(true);
                return t;
            });

    static {
        ((ThreadPoolExecutor) PREFETCH_EXECUTOR).allowCoreThreadTimeOut(true);
    }

    /** Galleries we have already kicked off a prefetch for in this process. */
    private static final Set<Long> PREFETCHED_GIDS =
            Collections.synchronizedSet(new HashSet<>());

    /** Lazily resolved and memoised cache directory so we don't re-stat it per call. */
    private static volatile File sCacheDir;

    private SmbPreviewCache() {}

    @NonNull
    private static File cacheDir() {
        File dir = sCacheDir;
        if (dir != null) {
            return dir;
        }
        synchronized (SmbPreviewCache.class) {
            dir = sCacheDir;
            if (dir == null) {
                dir = new File(EhApplication.getInstance().getCacheDir(), CACHE_SUBDIR);
                if (!dir.exists() && !dir.mkdirs()) {
                    // Don't memoise: a later call may succeed (disk full / permissions
                    // cleared). Otherwise every subsequent cacheFileFor() would return a
                    // path under a non-existent dir and every fetchOne() write would
                    // silently fail with no log + no retry (the gid is already in the
                    // PREFETCHED_GIDS dedup set).
                    Log.w(TAG, "Failed to create SMB preview cache dir: " + dir);
                    return dir;
                }
                sCacheDir = dir;
            }
        }
        return dir;
    }

    /**
     * Returns the deterministic cache file for a (gid, index) pair. The file may or may
     * not exist on disk — callers must check via {@link File#isFile()} before reading.
     */
    @NonNull
    public static File cacheFileFor(long gid, int index) {
        return new File(cacheDir(), gid + "-" + index);
    }

    /**
     * Kicks off a parallel SMB → local prefetch for every page of the gallery, exactly
     * once per process lifetime. Safe to call from any thread; returns immediately.
     *
     * <p>The fan-out loop itself runs on the prefetch pool rather than the caller's thread:
     * for big galleries (200+ pages) allocating N lambdas + N {@code LinkedBlockingQueue.put}
     * calls inline took long enough to cause a visible one-frame stutter when the preview
     * grid first bound on the UI thread.
     */
    public static void prefetchGallery(long gid, @Nullable String title, int pages) {
        if (pages <= 0 || !SmbStorage.isConfigured()) {
            return;
        }
        if (!PREFETCHED_GIDS.add(gid)) {
            return;
        }
        final GalleryInfo lookup = SmbStorage.lookupKey(gid, title);
        // One short-lived dispatch task; pages-many per-page tasks are queued from inside it.
        PREFETCH_EXECUTOR.execute(() -> dispatchPages(lookup, gid, pages));
    }

    private static void dispatchPages(@NonNull GalleryInfo lookup, long gid, int pages) {
        final AtomicInteger remaining = new AtomicInteger(pages);
        for (int i = 0; i < pages; i++) {
            final int index = i;
            PREFETCH_EXECUTOR.execute(() -> {
                try {
                    fetchOne(lookup, index);
                } catch (Throwable e) {
                    Log.w(TAG, "prefetch failed gid=" + gid + " index=" + index, e);
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        Log.i(TAG, "prefetch complete gid=" + gid + " pages=" + pages);
                    }
                }
            });
        }
    }

    private static void fetchOne(@NonNull GalleryInfo lookup, int index) throws IOException {
        File target = cacheFileFor(lookup.gid, index);
        if (target.isFile() && target.length() > 0) {
            return;
        }
        SmbFile remote = SmbStorage.findSmbImageFileForPreview(lookup, index);
        if (remote == null) {
            return;
        }
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = remote.getInputStream();
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
        if (!tmp.renameTo(target)) {
            // Another worker raced us — drop the tmp file.
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static void closeQuietly(@Nullable java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (IOException ignored) {}
        }
    }

    /** For test / cache-clear surfaces. */
    @SuppressWarnings("unused")
    public static void invalidateGallery(long gid) {
        PREFETCHED_GIDS.remove(gid);
    }
}
