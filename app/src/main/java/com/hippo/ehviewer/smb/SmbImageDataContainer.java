package com.hippo.ehviewer.smb;

import androidx.annotation.Nullable;

import com.hippo.conaco.DataContainer;
import com.hippo.conaco.ProgressNotifier;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.streampipe.InputStreamPipe;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Streams a single page image straight from the SMB share. Used as a Conaco
 * {@link DataContainer} for offline preview thumbnails.
 *
 * <p>{@link #get()} first checks the parallel {@link SmbPreviewCache} for a deterministic
 * local copy (populated by {@link SmbPreviewCache#prefetchGallery}); if present it returns
 * a {@link FileInputStream} pipe over that local file, which keeps Conaco's serial disk
 * thread free of SMB I/O. Falls back to {@link SmbStorage#openSmbInputStreamPipe} when
 * the prefetch hasn't completed the file yet.
 *
 * <p>Holds only the primitives needed to locate the file ({@code gid} and {@code title})
 * so it can sit on a GalleryDetail / preview-set object that is parcelled across scenes
 * without creating a back-reference cycle.
 */
public class SmbImageDataContainer implements DataContainer {

    private final long mGid;
    private final String mTitle;
    private final int mIndex;

    public SmbImageDataContainer(long gid, @Nullable String title, int index) {
        mGid = gid;
        mTitle = title;
        mIndex = index;
    }

    @Override
    public boolean isEnabled() {
        return SmbStorage.isConfigured();
    }

    @Override
    public void onUrlMoved(String requestUrl, String responseUrl) {
    }

    @Override
    public boolean save(InputStream is, long length, @Nullable String mediaType,
                        @Nullable ProgressNotifier notify) {
        // SMB is the authoritative copy; no need to cache the bytes back.
        return false;
    }

    @Nullable
    @Override
    public InputStreamPipe get() {
        File cached = SmbPreviewCache.cacheFileFor(mGid, mIndex);
        if (cached.isFile() && cached.length() > 0) {
            return new InputStreamPipe() {
                private FileInputStream fis;

                @Override public void obtain() {}
                @Override public void release() {}

                @Override
                public InputStream open() throws IOException {
                    if (fis != null) {
                        throw new IllegalStateException("Please close it first");
                    }
                    fis = new FileInputStream(cached);
                    return fis;
                }

                @Override
                public void close() {
                    IOUtils.closeQuietly(fis);
                    fis = null;
                }
            };
        }
        // Fallback: prefetch hasn't reached this page yet, fetch from SMB on this thread.
        return SmbStorage.openSmbInputStreamPipe(SmbStorage.lookupKey(mGid, mTitle), mIndex);
    }

    @Override
    public void remove() {
    }
}
