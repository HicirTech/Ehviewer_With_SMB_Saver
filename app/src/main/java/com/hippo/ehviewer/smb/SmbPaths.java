package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.lib.yorozuya.FileUtils;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Pure path/URL construction for the SMB layer.
 *
 * <p>Pulled out of {@code SmbStorage} so the bits that turn user-entered connection settings into
 * an {@code smb://} URL — and a {@link GalleryInfo} into a share folder name — can be unit-tested
 * without a live share or {@code Settings}/SharedPreferences. Everything here is a pure function of
 * its arguments; {@code SmbStorage} stays responsible for reading the values out of {@code Settings}
 * and passing them in.
 *
 * <p>Deliberately uses plain {@code null}/empty checks instead of {@code android.text.TextUtils} so
 * the class carries no Android framework dependency and the tests need no emulated SDK.
 */
public final class SmbPaths {

    private SmbPaths() {}

    /**
     * Builds the {@code smb://host[:port]/share/path/} URL jcifs uses to address the share root.
     *
     * <p>The default SMB port (445) is omitted to keep the URL canonical. Share names from typical
     * NAS configs may contain spaces or other reserved characters ("Public Documents", "Family$"),
     * so the share segment is percent-encoded; {@link URLEncoder} emits x-www-form-urlencoded
     * output, so its {@code "+"} is converted back to {@code "%20"} to stay within the smb-URL
     * grammar. The path segment is appended verbatim — callers are expected to pass it already
     * normalised (leading and trailing slash).
     */
    @NonNull
    public static String buildShareUrl(@Nullable String host, @Nullable String port,
                                       @Nullable String shareName, @Nullable String sharePath) {
        StringBuilder url = new StringBuilder("smb://");
        if (host != null) {
            url.append(host);
        }

        if (port != null && !port.isEmpty() && !port.equals("445")) {
            url.append(":").append(port);
        }

        String encodedShare = shareName != null ? shareName : "";
        if (!encodedShare.isEmpty()) {
            try {
                encodedShare = URLEncoder.encode(shareName, "UTF-8").replace("+", "%20");
            } catch (UnsupportedEncodingException ignored) {
                // UTF-8 is guaranteed; fall back to the raw value.
            }
        }
        url.append("/").append(encodedShare);
        if (sharePath != null) {
            url.append(sharePath);
        }
        return url.toString();
    }

    /**
     * The per-gallery folder name on the share: {@code <gid>-<title>}, sanitised to a filesystem-safe
     * string. Falls back to {@code "gallery"} when the gallery has no title.
     */
    @NonNull
    public static String buildGalleryFolderName(@NonNull GalleryInfo info) {
        String title = (info.title == null || info.title.isEmpty()) ? "gallery" : info.title;
        return FileUtils.sanitizeFilename(info.gid + "-" + title);
    }
}
