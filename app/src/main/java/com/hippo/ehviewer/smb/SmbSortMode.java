package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.Comparator;

/**
 * Ordering policy for the Local Inventory list.
 *
 * <p>Extracted from {@code SmbStorage} so the sort logic lives apart from SMB I/O and can be
 * unit-tested without a live share. The persisted value (see
 * {@link com.hippo.ehviewer.Settings#getLocalInventorySort()}) is the {@link #ordinal()} of one
 * of these constants, so the declaration order must stay stable.
 *
 * <p>Sorting operates on {@link Entry} values rather than {@link GalleryInfo} directly because
 * {@link #DOWNLOAD_DATE_DESC} keys off the share-side {@code metadata.json} mtime, which is not a
 * field on {@code GalleryInfo}. Carrying it alongside the gallery keeps every comparator a pure
 * function of its inputs.
 */
public enum SmbSortMode {
    /** Most recently downloaded first (mtime of metadata.json on the share). */
    DOWNLOAD_DATE_DESC,
    /** Most recently posted to the site first (uses {@link GalleryInfo#posted}). */
    POSTED_DATE_DESC,
    /** A-Z by title. */
    TITLE_ASC,
    /** Grouped by gallery category (doujinshi, manga, ...) then by title. */
    CATEGORY;

    /** Maps a persisted ordinal back to a mode, falling back to the default for stale values. */
    @NonNull
    public static SmbSortMode fromOrdinal(int o) {
        SmbSortMode[] all = values();
        return o >= 0 && o < all.length ? all[o] : DOWNLOAD_DATE_DESC;
    }

    /**
     * One gallery to be ordered, paired with when it was saved to the share. The download time is
     * the {@code metadata.json} mtime resolved by the inventory loader; {@code 0} when unknown.
     */
    public static final class Entry {
        @NonNull public final GalleryInfo info;
        public final long downloadedAtMillis;

        public Entry(@NonNull GalleryInfo info, long downloadedAtMillis) {
            this.info = info;
            this.downloadedAtMillis = downloadedAtMillis;
        }
    }

    /** Returns the comparator that realises this ordering. */
    @NonNull
    public Comparator<Entry> comparator() {
        switch (this) {
            case POSTED_DATE_DESC:
                // posted is a string like "2024-01-15 12:34" — reverse string order gives newest
                // first. Null posted dates sort as empty strings (i.e. last).
                return (a, b) -> postedOf(b.info).compareTo(postedOf(a.info));
            case TITLE_ASC:
                return (a, b) -> titleOf(a.info).compareToIgnoreCase(titleOf(b.info));
            case CATEGORY:
                return (a, b) -> {
                    int diff = Integer.compare(a.info.category, b.info.category);
                    if (diff != 0) {
                        return diff;
                    }
                    return titleOf(a.info).compareToIgnoreCase(titleOf(b.info));
                };
            case DOWNLOAD_DATE_DESC:
            default:
                return (a, b) -> Long.compare(b.downloadedAtMillis, a.downloadedAtMillis);
        }
    }

    @NonNull
    private static String postedOf(@NonNull GalleryInfo gi) {
        return gi.posted != null ? gi.posted : "";
    }

    /** Falls back title -> titleJpn -> empty so untitled galleries still sort deterministically. */
    @NonNull
    static String titleOf(@Nullable GalleryInfo gi) {
        if (gi == null) {
            return "";
        }
        if (gi.title != null) {
            return gi.title;
        }
        if (gi.titleJpn != null) {
            return gi.titleJpn;
        }
        return "";
    }
}
