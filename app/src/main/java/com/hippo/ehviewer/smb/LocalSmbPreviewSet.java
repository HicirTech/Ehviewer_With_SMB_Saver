package com.hippo.ehviewer.smb;

import android.os.Parcel;

import com.hippo.ehviewer.client.data.GalleryPreview;
import com.hippo.ehviewer.client.data.PreviewSet;
import com.hippo.widget.LoadImageView;

/**
 * A {@link PreviewSet} that points each preview slot at the corresponding
 * full-size image already stored on the SMB share. Used by the Local Inventory
 * detail page so the preview grid renders entirely offline.
 *
 * <p>Stores only the primitives needed to locate the gallery folder ({@code gid} and
 * {@code title}); does NOT hold a back-reference to its owning GalleryInfo, which
 * would create a cycle when both objects are parcelled (the GalleryDetail's
 * {@code previewSet} would point at us, and we'd point back at it → infinite
 * recursion → {@code StackOverflowError}).
 */
public class LocalSmbPreviewSet extends PreviewSet {

    private final long mGid;
    private final String mTitle;
    /** Number of previews this set exposes — a bounded slice, not the gallery's full page count. */
    private final int mCount;

    public LocalSmbPreviewSet(long gid, String title, int count) {
        mGid = gid;
        mTitle = title;
        mCount = Math.max(0, count);
    }

    @Override
    public int size() {
        return mCount;
    }

    @Override
    public int getPosition(int index) {
        return index;
    }

    @Override
    public String getPageUrlAt(int index) {
        // Preview taps in the detail scene launch the reader via R.id.index, not via the
        // page URL, so we don't need a real URL here.
        return "";
    }

    @Override
    public GalleryPreview getGalleryPreview(long gid, int index) {
        // Only consumed by GalleryPreviewsScene (the "view more previews" page), which
        // is unreachable in the offline flow because previewPages is capped to 1.
        return new GalleryPreview();
    }

    @Override
    public void load(LoadImageView view, long gid, int index) {
        // Kick off a parallel SMB → local-cache prefetch the first time any cell in this
        // gallery's preview grid asks to render. Conaco's per-cell loads still happen on
        // its serial disk thread, but each one becomes a fast local file read instead of
        // a sequential SMB round-trip.
        SmbPreviewCache.prefetchGallery(mGid, mTitle, mCount);
        view.resetClip();
        view.load(previewKey(gid, index), previewUrl(gid, index),
                new SmbImageDataContainer(mGid, mTitle, index), false, false);
    }

    private static String previewKey(long gid, int index) {
        return "smb-preview:" + gid + ":" + index;
    }

    private static String previewUrl(long gid, int index) {
        // Conaco needs a non-null URL, but with useNetwork=false the URL itself
        // is never fetched — the DataContainer supplies the bytes.
        return "smb-preview://" + gid + "/" + index;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mGid);
        dest.writeString(mTitle);
        dest.writeInt(mCount);
    }

    protected LocalSmbPreviewSet(Parcel in) {
        mGid = in.readLong();
        mTitle = in.readString();
        mCount = in.readInt();
    }

    public static final Creator<LocalSmbPreviewSet> CREATOR = new Creator<LocalSmbPreviewSet>() {
        @Override
        public LocalSmbPreviewSet createFromParcel(Parcel source) {
            return new LocalSmbPreviewSet(source);
        }

        @Override
        public LocalSmbPreviewSet[] newArray(int size) {
            return new LocalSmbPreviewSet[size];
        }
    };
}
