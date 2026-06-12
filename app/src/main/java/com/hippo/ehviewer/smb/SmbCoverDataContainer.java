package com.hippo.ehviewer.smb;

import androidx.annotation.Nullable;

import com.hippo.conaco.DataContainer;
import com.hippo.conaco.ProgressNotifier;
import com.hippo.streampipe.InputStreamPipe;

import java.io.InputStream;

/**
 * Conaco {@link DataContainer} that reads the gallery cover directly from the SMB share
 * instead of fetching {@link com.hippo.ehviewer.client.data.GalleryInfo#thumb} over the
 * network. Used by Local Inventory list cells so an offline SMB-only browse never hits
 * e-hentai for thumbnails that are already saved next to the gallery.
 *
 * <p>The actual on-share file is named {@code cover.<ext>} and was written at SMB-save
 * time by {@code SmbStorage.downloadAndWriteCover}. We probe every supported image
 * extension via {@link SmbStorage#openSmbCoverInputStreamPipe} since the upstream
 * Content-Type may have produced any of jpg/png/gif/webp.
 *
 * <p>Holds only primitives ({@code gid} + {@code title}) so the container can sit on a
 * parcelled GalleryInfo without creating a back-reference cycle.
 */
public class SmbCoverDataContainer implements DataContainer {

    private final long mGid;
    @Nullable private final String mTitle;

    public SmbCoverDataContainer(long gid, @Nullable String title) {
        mGid = gid;
        mTitle = title;
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
        // SMB is the authoritative copy; if Conaco still falls back to network here we
        // intentionally don't re-write it back.
        return false;
    }

    @Nullable
    @Override
    public InputStreamPipe get() {
        return SmbStorage.openSmbCoverInputStreamPipe(SmbStorage.lookupKey(mGid, mTitle));
    }

    @Override
    public void remove() {
    }
}
