package com.hippo.ehviewer.smb;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryComment;
import com.hippo.ehviewer.client.data.GalleryCommentList;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.GalleryTagGroup;

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
            // IMPORTANT: do NOT pass `gd` itself — that would create a cycle
            // (gd.previewSet -> us -> gd) which crashes when parcelled.
            gd.previewSet = new LocalSmbPreviewSet(gd.gid, gd.title, gd.pages);
        }
        // Tell bindPreviews there's something to render. Set to 1 so the grid renders
        // the single page of previews and the "more previews" hint stays as
        // R.string.no_more_previews.
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
}
