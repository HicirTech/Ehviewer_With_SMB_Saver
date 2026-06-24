package com.hippo.ehviewer.smb;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryComment;
import com.hippo.ehviewer.client.data.GalleryCommentList;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.GalleryTagGroup;
import com.hippo.util.IoThreadPoolExecutor;

import jcifs.smb.SmbFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Turns the locally-stored gallery info (read from {@code metadata.json}) into the in-memory
 * objects the UI needs to render a gallery fully offline.
 *
 * <p>Pulled out of {@code SmbStorage} so the metadata-to-model assembly lives apart from the SMB
 * I/O. Everything here is an in-memory transform — no share access, no network — so {@code
 * SmbStorage} stays responsible for the actual reads/writes and hands the parsed info in.
 */
public final class SmbMetadata {

    private static final String TAG = "SmbMetadata";

    /**
     * How many previews to render in the offline detail page. Capped on purpose: a large gallery
     * would otherwise put one grid cell — and one SMB prefetch — per page, freezing the detail
     * scene and blowing up memory on open. The remaining pages stay reachable through the reader
     * (tapping a preview). Browsing every preview offline is a separate follow-up.
     */
    private static final int DETAIL_PREVIEW_LIMIT = 40;

    private SmbMetadata() {}

    /**
     * Builds a {@link GalleryDetail} populated from the locally-stored info, so the gallery
     * detail screen can render without making a network call. Tag groups are reconstructed
     * from {@link GalleryInfo#tgList}. Detail-only fields (comments, previews, language…)
     * are filled with safe empty defaults to avoid NPEs in {@code GalleryDetailScene}.
     */
    @NonNull
    public static GalleryDetail buildOfflineDetail(@NonNull GalleryInfo info) {
        GalleryDetail gd;
        if (info instanceof GalleryDetail) {
            gd = (GalleryDetail) info;
        } else {
            gd = new GalleryDetail();
            gd.gid = info.gid;
            gd.token = info.token;
            gd.title = info.title;
            gd.titleJpn = info.titleJpn;
            gd.thumb = info.thumb;
            gd.category = info.category;
            gd.posted = info.posted;
            gd.uploader = info.uploader;
            gd.rating = info.rating;
            gd.rated = info.rated;
            gd.simpleLanguage = info.simpleLanguage;
            gd.pages = info.pages;
            gd.favoriteSlot = info.favoriteSlot;
            gd.favoriteName = info.favoriteName;
            gd.simpleTags = info.simpleTags;
            gd.tgList = info.tgList;
            gd.thumbWidth = info.thumbWidth;
            gd.thumbHeight = info.thumbHeight;
        }
        if (gd.tags == null || gd.tags.length == 0) {
            gd.tags = buildTagGroupsFromList(gd.tgList);
        }
        if (gd.comments == null) {
            gd.comments = new GalleryCommentList(new GalleryComment[0], false);
        }
        if (gd.previewSet == null || gd.previewSet.size() == 0) {
            // Bounded first slice of previews — not one cell per gallery page. IMPORTANT: do NOT
            // pass `gd` itself — that would create a cycle (gd.previewSet -> us -> gd) which
            // crashes when parcelled.
            int previewCount = Math.min(gd.pages, DETAIL_PREVIEW_LIMIT);
            gd.previewSet = new LocalSmbPreviewSet(gd.gid, gd.title, previewCount);
        }
        // Tell bindPreviews there's something to render. Set to 1 so the grid renders the single
        // (capped) page of previews and the "more previews" hint stays as R.string.no_more_previews
        // — the rest of the pages are viewed through the reader.
        gd.previewPages = gd.pages > 0 ? 1 : 0;
        if (gd.language == null) {
            gd.language = gd.simpleLanguage != null ? gd.simpleLanguage : "";
        }
        if (gd.size == null) gd.size = "";
        if (gd.parent == null) gd.parent = "";
        if (gd.visible == null) gd.visible = "";
        if (gd.torrentUrl == null) gd.torrentUrl = "";
        if (gd.archiveUrl == null) gd.archiveUrl = "";
        return gd;
    }

    /**
     * Rebuilds the structured tag groups from the flat {@code group:tag} strings stored in
     * {@link GalleryInfo#tgList}. Entries without a group prefix fall into a {@code "misc"} group.
     */
    @NonNull
    static GalleryTagGroup[] buildTagGroupsFromList(@Nullable List<String> tgList) {
        if (tgList == null || tgList.isEmpty()) {
            return new GalleryTagGroup[0];
        }
        LinkedHashMap<String, GalleryTagGroup> groups = new LinkedHashMap<>();
        for (String entry : tgList) {
            if (TextUtils.isEmpty(entry)) {
                continue;
            }
            int idx = entry.indexOf(':');
            String groupName;
            String tag;
            if (idx <= 0) {
                groupName = "misc";
                tag = entry;
            } else {
                groupName = entry.substring(0, idx);
                tag = entry.substring(idx + 1);
            }
            if (TextUtils.isEmpty(tag)) {
                continue;
            }
            GalleryTagGroup g = groups.get(groupName);
            if (g == null) {
                g = new GalleryTagGroup();
                g.groupName = groupName;
                groups.put(groupName, g);
            }
            g.addTag(tag);
        }
        return groups.values().toArray(new GalleryTagGroup[0]);
    }

    // --- metadata.json writes -------------------------------------------------------------------

    /**
     * Writes a minimal metadata.json from the GalleryInfo immediately, so the gallery shows up
     * in Local Inventory even before/without a finished download. Safe to call repeatedly.
     */
    public static boolean writeMetadataSkeleton(@NonNull GalleryInfo info) {
        try {
            SmbFile galleryDir = SmbStorage.getGalleryDir(info);
            writeMetadata(galleryDir, info);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to write skeleton metadata gid=" + info.gid, e);
            return false;
        }
    }

    /**
     * If the locally-stored info lacks tags, fetch the gallery detail in the background and
     * rewrite metadata.json so subsequent opens are fully offline. No-op if tags are already
     * present or SMB is not configured.
     */
    public static void enrichLocalMetadataIfMissing(@NonNull Context context, @NonNull GalleryInfo info) {
        if (info.tgList != null && !info.tgList.isEmpty()) {
            return;
        }
        if (!SmbStorage.isConfigured()) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                SmbFile galleryDir = SmbStorage.getGalleryDir(info);
                if (!galleryDir.exists()) {
                    return;
                }
                writeMetadataWithDetail(appContext, galleryDir, info);
            } catch (Throwable e) {
                Log.w(TAG, "Failed to enrich local metadata gid=" + info.gid, e);
            }
        });
    }

    private static void writeMetadata(@NonNull SmbFile galleryDir, @NonNull GalleryInfo info) throws IOException {
        SmbFile metadata = new SmbFile(galleryDir, SmbStorage.METADATA_FILE);
        String json = info.toJson().toJSONString();
        try (OutputStream os = metadata.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    static void writeMetadataWithDetail(@NonNull Context context, @NonNull SmbFile galleryDir, @NonNull GalleryInfo info) throws IOException {
        writeMetadataWithDetail(context, galleryDir, info, info.pages);
    }

    static void writeMetadataWithDetail(@NonNull Context context, @NonNull SmbFile galleryDir,
                                        @NonNull GalleryInfo info, int fallbackPages) throws IOException {
        GalleryInfo enriched = enrichWithGalleryTags(context, info, fallbackPages);
        writeMetadata(galleryDir, enriched);
    }

    @NonNull
    private static GalleryInfo enrichWithGalleryTags(@NonNull Context context, @NonNull GalleryInfo info, int fallbackPages) {
        try {
            String detailUrl = EhUrl.getGalleryDetailUrl(info.gid, info.token);
            GalleryDetail detail = EhEngine.getGalleryDetail(null, EhApplication.getOkHttpClient(context), detailUrl);
            if (detail == null) {
                return info;
            }

            // Supplement any fields the detail page didn't fill from the original info.
            if (TextUtils.isEmpty(detail.title)) detail.title = info.title;
            if (TextUtils.isEmpty(detail.titleJpn)) detail.titleJpn = info.titleJpn;
            if (TextUtils.isEmpty(detail.thumb)) detail.thumb = info.thumb;
            if (detail.category == 0) detail.category = info.category;
            if (TextUtils.isEmpty(detail.posted)) detail.posted = info.posted;
            if (TextUtils.isEmpty(detail.uploader)) detail.uploader = info.uploader;
            if (detail.pages <= 0) detail.pages = fallbackPages > 0 ? fallbackPages : info.pages;
            if (TextUtils.isEmpty(detail.simpleLanguage)) detail.simpleLanguage = info.simpleLanguage;

            if (detail.tags != null) {
                List<String> allTags = new ArrayList<>();
                for (GalleryTagGroup group : detail.tags) {
                    if (group == null || TextUtils.isEmpty(group.groupName)) {
                        continue;
                    }
                    for (int i = 0; i < group.size(); i++) {
                        String tag = group.getTagAt(i);
                        if (!TextUtils.isEmpty(tag)) {
                            allTags.add(group.groupName + ":" + tag);
                        }
                    }
                }
                if (!allTags.isEmpty()) {
                    detail.simpleTags = allTags.toArray(new String[0]);
                    detail.tgList = new ArrayList<>(allTags);
                    detail.generateSLang();
                }
            }
            return detail;
        } catch (Throwable e) {
            Log.w(TAG, "Failed to enrich tags from gallery detail gid=" + info.gid, e);
            return info;
        }
    }
}
