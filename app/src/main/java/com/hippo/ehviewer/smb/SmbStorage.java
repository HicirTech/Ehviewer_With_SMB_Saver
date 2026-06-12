package com.hippo.ehviewer.smb;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryComment;
import com.hippo.ehviewer.client.data.GalleryCommentList;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.GalleryTagGroup;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;
import com.hippo.lib.yorozuya.FileUtils;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.unifile.UniFile;
import com.hippo.util.IoThreadPoolExecutor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

import jcifs.CIFSContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class SmbStorage {

    private static final String TAG = "SmbStorage";
    private static final String METADATA_FILE = "metadata.json";
    private static final String SPIDER_INFO_FILE = ".ehviewer";

    /**
     * Per-gid intent mark for routing reads/writes to SMB. Replaces the old global
     * {@code Settings.getSmbSaveEnabled()} routing flag — that was leaking phone downloads
     * onto the SMB share whenever the master toggle was on. Now only galleries explicitly
     * marked here (by {@link SmbDirectDownloader} or {@code LocalInventoryScene.openReader})
     * use SMB I/O. Regular DownloadManager downloads always go to phone storage.
     */
    private static final java.util.Set<Long> SMB_TARGET_GIDS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private SmbStorage() {}

    public static void markGidAsSmbTarget(long gid) {
        SMB_TARGET_GIDS.add(gid);
    }

    public static void unmarkGidAsSmbTarget(long gid) {
        SMB_TARGET_GIDS.remove(gid);
    }

    public static boolean isGidMarkedSmbTarget(long gid) {
        return SMB_TARGET_GIDS.contains(gid);
    }

    public static boolean isConfigured() {
        return !TextUtils.isEmpty(Settings.getSmbHost()) &&
                !TextUtils.isEmpty(Settings.getSmbShareName());
    }

    @NonNull
    private static CIFSContext buildContext() {
        String username = Settings.getSmbUsername();
        String password = Settings.getSmbPassword();
        if (TextUtils.isEmpty(username)) {
            return SingletonContext.getInstance();
        }
        NtlmPasswordAuthenticator authenticator = new NtlmPasswordAuthenticator(null, username, password);
        return SingletonContext.getInstance().withCredentials(authenticator);
    }

    @NonNull
    private static String buildSmbUrl() {
        String host = Settings.getSmbHost();
        String port = Settings.getSmbPort();
        String shareName = Settings.getSmbShareName();
        String sharePath = Settings.getSmbSharePath();

        StringBuilder url = new StringBuilder("smb://");
        url.append(host);

        if (!TextUtils.isEmpty(port) && !port.equals("445")) {
            url.append(":").append(port);
        }

        // Share names from typical NAS configs may contain spaces or other reserved
        // characters ("Public Documents", "Family$"). jcifs needs them percent-encoded
        // in the URL form. URLEncoder yields x-www-form-urlencoded output, so convert
        // its "+" back to "%20" to stay within the smb-URL grammar.
        String encodedShare = shareName;
        if (!TextUtils.isEmpty(shareName)) {
            try {
                encodedShare = URLEncoder.encode(shareName, "UTF-8").replace("+", "%20");
            } catch (UnsupportedEncodingException ignored) {
                // UTF-8 is guaranteed; fall back to the raw value.
            }
        }
        url.append("/").append(encodedShare).append(sharePath);
        return url.toString();
    }

    @NonNull
    public static String buildGalleryFolderName(@NonNull GalleryInfo info) {
        return FileUtils.sanitizeFilename(info.gid + "-" + (TextUtils.isEmpty(info.title) ? "gallery" : info.title));
    }

    /**
     * Builds a lightweight {@link GalleryInfo} carrying just the fields needed by SMB
     * lookup helpers ({@code gid} + {@code title}). Used from contexts that must avoid
     * holding a back-reference to a full GalleryInfo (e.g. parcelable preview sets).
     */
    @NonNull
    public static GalleryInfo lookupKey(long gid, @Nullable String title) {
        GalleryInfo info = new GalleryInfo();
        info.gid = gid;
        info.title = title;
        return info;
    }

    @NonNull
    private static SmbFile getGalleryDir(@NonNull GalleryInfo info) throws IOException {
        CIFSContext cifs = buildContext();
        SmbFile shareRoot = new SmbFile(buildSmbUrl(), cifs);
        if (!shareRoot.exists()) {
            shareRoot.mkdirs();
        }
        SmbFile galleryDir = new SmbFile(shareRoot, buildGalleryFolderName(info) + "/");
        if (!galleryDir.exists()) {
            galleryDir.mkdirs();
        }
        return galleryDir;
    }

    public static boolean prepareGalleryDir(@NonNull GalleryInfo info) {
        try {
            getGalleryDir(info);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to prepare SMB gallery dir gid=" + info.gid, e);
            return false;
        }
    }

    public static boolean isGallerySynced(@NonNull GalleryInfo info) {
        try {
            SmbFile metadata = new SmbFile(getGalleryDir(info), METADATA_FILE);
            return metadata.exists();
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Recursively deletes the on-share gallery folder. Used when a SMB download task is
     * cancelled — leaving partial pages behind would clutter the share and confuse a later
     * resume / re-enqueue (since {@link #isGalleryComplete} could count stale pages).
     * Returns true if the folder was deleted or never existed.
     */
    public static boolean deleteGalleryFolder(@NonNull GalleryInfo info) {
        try {
            // Build the directory reference without auto-creating it (getGalleryDir would
            // mkdirs() on a missing dir, then we'd immediately try to delete what we just
            // created — wasteful at best, wrong at worst if the dir never existed).
            CIFSContext cifs = buildContext();
            SmbFile shareRoot = new SmbFile(buildSmbUrl(), cifs);
            SmbFile galleryDir = new SmbFile(shareRoot, buildGalleryFolderName(info) + "/");
            if (!galleryDir.exists()) {
                return true;
            }
            // jcifs-ng's SmbFile.delete() throws SmbException (STATUS_DIRECTORY_NOT_EMPTY)
            // when the directory is non-empty. Delete all contents first, then the dir.
            deleteSmbDirRecursive(galleryDir);
            return !galleryDir.exists();
        } catch (Throwable e) {
            Log.w(TAG, "Failed to delete SMB gallery folder gid=" + info.gid, e);
            return false;
        }
    }

    /**
     * Recursively deletes {@code dir} and all of its contents on the SMB share.
     * jcifs-ng requires a directory to be empty before {@link SmbFile#delete()} succeeds,
     * so we traverse depth-first and delete files before their parent directories.
     * <p>
     * Deletion is best-effort: individual child failures are logged and skipped so that
     * remaining siblings are still processed. The parent directory delete at the end will
     * propagate any {@link IOException} if not all children were removed.
     */
    private static void deleteSmbDirRecursive(@NonNull SmbFile dir) throws IOException {
        SmbFile[] children = dir.listFiles();
        if (children != null) {
            for (SmbFile child : children) {
                try {
                    if (child.isDirectory()) {
                        deleteSmbDirRecursive(child);
                    } else {
                        child.delete();
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Could not delete SMB entry: " + child.getPath(), e);
                }
            }
        }
        dir.delete();
    }

    /**
     * Returns true when the on-share copy has a metadata.json declaring a positive
     * page count and the same number of image files are present on the share. Used by
     * the SMB download path to skip galleries that are already fully saved.
     * Performs SMB I/O — must be called from a worker thread.
     */
    public static boolean isGalleryComplete(@NonNull GalleryInfo info) {
        try {
            SmbFile galleryDir = getGalleryDir(info);
            SmbFile metadata = new SmbFile(galleryDir, METADATA_FILE);
            if (!metadata.exists()) {
                return false;
            }
            int declaredPages = info.pages;
            try (InputStream is = metadata.getInputStream()) {
                String json = readAll(is);
                JSONObject obj = JSONObject.parseObject(json);
                if (obj != null) {
                    Integer p = obj.getInteger("pages");
                    if (p != null && p > 0) {
                        declaredPages = p;
                    }
                }
            } catch (Throwable ignored) {
            }
            if (declaredPages <= 0) {
                int spiderPages = readPagesFromSpiderInfo(info);
                if (spiderPages > 0) declaredPages = spiderPages;
            }
            if (declaredPages <= 0) {
                return false;
            }
            int saved = countSavedImages(galleryDir, declaredPages);
            return saved >= declaredPages;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Counts pages that have at least one image file in the gallery folder. Uses a single
     * {@code listFiles()} call to avoid N×{extensions} SMB round-trips (which previously
     * caused OOMs on large galleries because each round-trip allocated jcifs buffers).
     */
    private static int countSavedImages(@NonNull SmbFile galleryDir, int pageCount) throws IOException {
        String[] names = galleryDir.list();
        if (names == null || names.length == 0) {
            return 0;
        }
        java.util.HashSet<String> present = new java.util.HashSet<>(names.length * 2);
        java.util.Collections.addAll(present, names);
        int count = 0;
        for (int i = 0; i < pageCount; i++) {
            for (String extension : com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
                if (present.contains(SpiderDen.generateImageFilename(i, extension))) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    @Nullable
    public static OutputStream openSpiderInfoOutputStream(@NonNull GalleryInfo info) {
        try {
            SmbFile file = new SmbFile(getGalleryDir(info), SPIDER_INFO_FILE);
            return file.getOutputStream();
        } catch (Throwable e) {
            Log.e(TAG, "Failed to open SMB spider_info output gid=" + info.gid, e);
            return null;
        }
    }

    @Nullable
    public static InputStream openSpiderInfoInputStream(@NonNull GalleryInfo info) {
        try {
            SmbFile file = new SmbFile(getGalleryDir(info), SPIDER_INFO_FILE);
            if (!file.exists()) {
                return null;
            }
            return file.getInputStream();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Writes a minimal metadata.json from the GalleryInfo immediately, so the gallery shows up
     * in Local Inventory even before/without a finished download. Safe to call repeatedly.
     */
    public static boolean writeMetadataSkeleton(@NonNull GalleryInfo info) {
        try {
            SmbFile galleryDir = getGalleryDir(info);
            writeMetadata(galleryDir, info);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to write skeleton metadata gid=" + info.gid, e);
            return false;
        }
    }

    @Nullable
    private static SmbFile findSmbImageFile(@NonNull GalleryInfo info, int index) throws IOException {
        SmbFile galleryDir = getGalleryDir(info);
        for (String extension : com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            String filename = SpiderDen.generateImageFilename(index, extension);
            SmbFile file = new SmbFile(galleryDir, filename);
            if (file.exists()) {
                return file;
            }
        }
        return null;
    }

    /** Package-visible accessor used by {@link SmbPreviewCache} for parallel prefetch. */
    @Nullable
    static SmbFile findSmbImageFileForPreview(@NonNull GalleryInfo info, int index) {
        try {
            return findSmbImageFile(info, index);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Locates the {@code cover.<ext>} file written by {@link #downloadAndWriteCover}. We try
     * every image extension the rest of the stack supports, since cover's extension depends
     * on the upstream Content-Type at the time of save.
     */
    @Nullable
    private static SmbFile findSmbCoverFile(@NonNull GalleryInfo info) throws IOException {
        SmbFile galleryDir = getGalleryDir(info);
        for (String extension : com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            SmbFile file = new SmbFile(galleryDir, "cover" + extension);
            if (file.exists()) {
                return file;
            }
        }
        return null;
    }

    /**
     * Stage the on-share cover to a local temp file and return a {@link FileInputStream}-backed
     * pipe. Conaco's image decoder requires a real file descriptor (same constraint as page
     * loads), so SmbFileInputStream cannot be returned directly.
     */
    @Nullable
    public static InputStreamPipe openSmbCoverInputStreamPipe(@NonNull GalleryInfo info) {
        try {
            final SmbFile file = findSmbCoverFile(info);
            if (file == null) {
                return null;
            }
            return new InputStreamPipe() {
                private java.io.FileInputStream fis;
                private java.io.File tempFile;

                @Override public void obtain() {}

                @Override public void release() {}

                @Override
                public InputStream open() throws IOException {
                    if (fis != null) {
                        throw new IllegalStateException("Please close it first");
                    }
                    java.io.File dir = new java.io.File(
                            EhApplication.getInstance().getCacheDir(), "smb_tmp");
                    if (!dir.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        dir.mkdirs();
                    }
                    tempFile = java.io.File.createTempFile("smb_cover_", null, dir);
                    InputStream remote = null;
                    OutputStream local = null;
                    try {
                        remote = file.getInputStream();
                        local = new java.io.FileOutputStream(tempFile);
                        IOUtils.copy(remote, local);
                    } finally {
                        IOUtils.closeQuietly(remote);
                        IOUtils.closeQuietly(local);
                    }
                    fis = new java.io.FileInputStream(tempFile);
                    return fis;
                }

                @Override
                public void close() {
                    IOUtils.closeQuietly(fis);
                    fis = null;
                    if (tempFile != null) {
                        //noinspection ResultOfMethodCallIgnored
                        tempFile.delete();
                        tempFile = null;
                    }
                }
            };
        } catch (Throwable e) {
            Log.e(TAG, "Failed to open SMB cover pipe gid=" + info.gid, e);
            return null;
        }
    }

    public static boolean containImage(@NonNull GalleryInfo info, int index) {
        try {
            return findSmbImageFile(info, index) != null;
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean removeImage(@NonNull GalleryInfo info, int index) {
        boolean result = false;
        try {
            SmbFile galleryDir = getGalleryDir(info);
            for (String extension : com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
                String filename = SpiderDen.generateImageFilename(index, extension);
                SmbFile file = new SmbFile(galleryDir, filename);
                if (file.exists()) {
                    file.delete();
                    result = true;
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to remove SMB image gid=" + info.gid + ", index=" + index, e);
        }
        return result;
    }

    @Nullable
    public static OutputStreamPipe openSmbOutputStreamPipe(@NonNull GalleryInfo info, int index, @Nullable String extension) {
        try {
            String ext = extension;
            if (TextUtils.isEmpty(ext)) {
                ext = com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS[0];
            }
            if (!ext.startsWith(".")) {
                ext = "." + ext;
            }
            SmbFile target = new SmbFile(getGalleryDir(info), SpiderDen.generateImageFilename(index, ext));
            final SmbFile finalTarget = target;
            return new OutputStreamPipe() {
                private OutputStream os;

                @Override
                public void obtain() {
                    // no-op
                }

                @Override
                public void release() {
                    // no-op
                }

                @Override
                public OutputStream open() throws IOException {
                    if (os != null) {
                        throw new IllegalStateException("Please close it first");
                    }
                    os = finalTarget.getOutputStream();
                    return os;
                }

                @Override
                public void close() {
                    IOUtils.closeQuietly(os);
                    os = null;
                }
            };
        } catch (Throwable e) {
            Log.e(TAG, "Failed to open SMB output pipe gid=" + info.gid + ", index=" + index, e);
            return null;
        }
    }

    @Nullable
    public static InputStreamPipe openSmbInputStreamPipe(@NonNull GalleryInfo info, int index) {
        try {
            final SmbFile file = findSmbImageFile(info, index);
            if (file == null) {
                return null;
            }
            // SpiderDecoder casts the InputStream to FileInputStream and Image.decode requires
            // a real file descriptor for the native decoder. SmbFileInputStream is NOT a
            // FileInputStream, so we materialize the SMB content into a local temp file and
            // hand back a FileInputStream over that temp file. The temp file is removed when
            // the pipe is closed.
            return new InputStreamPipe() {
                private java.io.FileInputStream fis;
                private java.io.File tempFile;

                @Override
                public void obtain() {
                    // no-op
                }

                @Override
                public void release() {
                    // no-op
                }

                @Override
                public InputStream open() throws IOException {
                    if (fis != null) {
                        throw new IllegalStateException("Please close it first");
                    }
                    java.io.File dir = new java.io.File(
                            EhApplication.getInstance().getCacheDir(), "smb_tmp");
                    if (!dir.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        dir.mkdirs();
                    }
                    tempFile = java.io.File.createTempFile("smb_img_", null, dir);
                    InputStream remote = null;
                    OutputStream local = null;
                    try {
                        remote = file.getInputStream();
                        local = new java.io.FileOutputStream(tempFile);
                        IOUtils.copy(remote, local);
                    } finally {
                        IOUtils.closeQuietly(remote);
                        IOUtils.closeQuietly(local);
                    }
                    fis = new java.io.FileInputStream(tempFile);
                    return fis;
                }

                @Override
                public void close() {
                    IOUtils.closeQuietly(fis);
                    fis = null;
                    if (tempFile != null) {
                        //noinspection ResultOfMethodCallIgnored
                        tempFile.delete();
                        tempFile = null;
                    }
                }
            };
        } catch (Throwable e) {
            Log.e(TAG, "Failed to open SMB input pipe gid=" + info.gid + ", index=" + index, e);
            return null;
        }
    }

    public static void testConnection() throws IOException {
        String host = Settings.getSmbHost();
        String shareName = Settings.getSmbShareName();

        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(shareName)) {
            // User-facing — surfaced through the Settings toast.
            throw new IOException(EhApplication.getInstance()
                    .getString(R.string.smb_test_error_unconfigured));
        }

        CIFSContext cifs = buildContext();
        SmbFile shareRoot = new SmbFile(buildSmbUrl(), cifs);
        if (!shareRoot.exists()) {
            throw new IOException(EhApplication.getInstance()
                    .getString(R.string.smb_test_error_share_not_accessible));
        }
    }

    public static void syncDownloadedGallery(@NonNull Context context, @NonNull DownloadInfo info) throws IOException {
        if (!isConfigured()) {
            throw new IOException("SMB is not configured");
        }

        UniFile localDir = SpiderDen.getGalleryDownloadDir(info);
        if (localDir == null || !localDir.isDirectory()) {
            throw new IOException("Local download folder not found");
        }

        CIFSContext cifs = buildContext();
        SmbFile shareRoot = new SmbFile(buildSmbUrl(), cifs);
        if (!shareRoot.exists()) {
            shareRoot.mkdirs();
        }

        SmbFile galleryDir = new SmbFile(shareRoot, buildGalleryFolderName(info) + "/");
        if (!galleryDir.exists()) {
            galleryDir.mkdirs();
        }

        copyUniDir(localDir, galleryDir);
        writeMetadataWithDetail(context, galleryDir, info);
        downloadAndWriteCover(context, galleryDir, info);
    }

    public static void finalizeDownloadedGallery(@NonNull Context context, @NonNull GalleryInfo info) {
        try {
            SmbFile galleryDir = getGalleryDir(info);
            // Resolve the real page count if the caller didn't already have it (e.g. info came
            // from a search list). We deliberately do NOT mutate `info.pages` here — the same
            // GalleryInfo instance is held by SmbDirectDownloader.active and can be observed
            // concurrently from the main thread (task snapshots, notifications). Passing the
            // resolved value down keeps the write thread-local.
            int resolvedPages = info.pages;
            if (resolvedPages <= 0) {
                int spiderPages = readPagesFromSpiderInfo(info);
                if (spiderPages > 0) {
                    resolvedPages = spiderPages;
                }
            }
            writeMetadataWithDetail(context, galleryDir, info, resolvedPages);
            downloadAndWriteCover(context, galleryDir, info);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to finalize SMB gallery gid=" + info.gid, e);
        }
    }

    private static int readPagesFromSpiderInfo(@NonNull GalleryInfo info) {
        InputStream is = openSpiderInfoInputStream(info);
        if (is == null) {
            return 0;
        }
        try {
            com.hippo.ehviewer.spider.SpiderInfo spiderInfo = com.hippo.ehviewer.spider.SpiderInfo.read(is);
            return spiderInfo != null ? spiderInfo.pages : 0;
        } catch (Throwable e) {
            return 0;
        } finally {
            IOUtils.closeQuietly(is);
        }
    }

    private static void copyUniDir(@NonNull UniFile srcDir, @NonNull SmbFile targetDir) throws IOException {
        UniFile[] children = srcDir.listFiles();
        if (children == null) {
            return;
        }
        for (UniFile child : children) {
            String name = child.getName();
            if (name == null) {
                continue;
            }
            if (child.isDirectory()) {
                SmbFile subDir = new SmbFile(targetDir, name + "/");
                if (!subDir.exists()) {
                    subDir.mkdirs();
                }
                copyUniDir(child, subDir);
            } else {
                SmbFile targetFile = new SmbFile(targetDir, name);
                // Open the source first, hold it in a local so it's closed even if opening
                // the SMB output stream throws (Java evaluates args left-to-right, so a
                // throw from getOutputStream() would otherwise leak the already-open input).
                InputStream in = child.openInputStream();
                OutputStream out;
                try {
                    out = targetFile.getOutputStream();
                } catch (IOException e) {
                    IOUtils.closeQuietly(in);
                    throw e;
                }
                copyStream(in, out);
            }
        }
    }

    private static void writeMetadata(@NonNull SmbFile galleryDir, @NonNull GalleryInfo info) throws IOException {
        SmbFile metadata = new SmbFile(galleryDir, METADATA_FILE);
        String json = info.toJson().toJSONString();
        try (OutputStream os = metadata.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeMetadataWithDetail(@NonNull Context context, @NonNull SmbFile galleryDir, @NonNull GalleryInfo info) throws IOException {
        writeMetadataWithDetail(context, galleryDir, info, info.pages);
    }

    private static void writeMetadataWithDetail(@NonNull Context context, @NonNull SmbFile galleryDir,
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

    private static void downloadAndWriteCover(@NonNull Context context, @NonNull SmbFile galleryDir, @NonNull GalleryInfo info) {
        if (TextUtils.isEmpty(info.thumb)) {
            return;
        }
        String thumbUrl = info.thumb;
        if (thumbUrl.startsWith("//")) {
            thumbUrl = "https:" + thumbUrl;
        }
        OkHttpClient client = EhApplication.getOkHttpClient(context);
        Request request = new Request.Builder().url(thumbUrl).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return;
            }
            String extension = ".jpg";
            String contentType = response.body().contentType() != null ? response.body().contentType().toString() : null;
            if (contentType != null) {
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType);
                if (!TextUtils.isEmpty(ext)) {
                    extension = "." + ext;
                }
            }
            SmbFile coverFile = new SmbFile(galleryDir, "cover" + extension);
            // Open source first; the response body's byteStream is owned by the response
            // (closed via try-with-resources) so we just need to make sure the SMB output
            // open failing doesn't drop a still-uncopied body on the floor.
            InputStream in = response.body().byteStream();
            OutputStream out;
            try {
                out = coverFile.getOutputStream();
            } catch (IOException e) {
                IOUtils.closeQuietly(in);
                throw e;
            }
            copyStream(in, out);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to download cover", e);
        }
    }

    /**
     * How the local inventory list should be ordered. Persisted via
     * {@link com.hippo.ehviewer.Settings#getLocalInventorySort()}; the int stored there is the
     * ordinal of one of these constants — keep the order stable.
     */
    public enum SortMode {
        /** Most recently downloaded first (mtime of metadata.json on the share). */
        DOWNLOAD_DATE_DESC,
        /** Most recently posted to the site first (uses {@link GalleryInfo#posted}). */
        POSTED_DATE_DESC,
        /** A-Z by title. */
        TITLE_ASC,
        /** Grouped by gallery category (doujinshi, manga, ...) then by title. */
        CATEGORY;

        public static SortMode fromOrdinal(int o) {
            SortMode[] all = values();
            return o >= 0 && o < all.length ? all[o] : DOWNLOAD_DATE_DESC;
        }
    }

    @NonNull
    public static List<GalleryInfo> loadInventory() {
        return loadInventory(SortMode.DOWNLOAD_DATE_DESC);
    }

    @NonNull
    public static List<GalleryInfo> loadInventory(@NonNull SortMode mode) {
        if (!isConfigured()) {
            return new ArrayList<>();
        }

        // Parallel arrays so we can sort by metadata.json mtime without smearing
        // download time onto GalleryInfo (which has no such field).
        List<GalleryInfo> infos = new ArrayList<>();
        List<Long> downloadedAt = new ArrayList<>();
        try {
            CIFSContext cifs = buildContext();
            SmbFile shareRoot = new SmbFile(buildSmbUrl(), cifs);
            if (!shareRoot.exists() || !shareRoot.isDirectory()) {
                return new ArrayList<>();
            }
            SmbFile[] children = shareRoot.listFiles();
            if (children == null) {
                return new ArrayList<>();
            }
            for (SmbFile child : children) {
                if (!child.isDirectory()) {
                    continue;
                }
                SmbFile metadata = new SmbFile(child, METADATA_FILE);
                if (!metadata.exists()) {
                    continue;
                }
                String json;
                try (InputStream is = metadata.getInputStream()) {
                    json = readAll(is);
                }
                JSONObject object = JSONObject.parseObject(json);
                if (object == null) {
                    continue;
                }
                GalleryInfo info = GalleryInfo.galleryInfoFromJson(object);
                infos.add(info);
                long mtime;
                try {
                    mtime = metadata.lastModified();
                } catch (Throwable ignored) {
                    mtime = 0L;
                }
                downloadedAt.add(mtime);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to load SMB inventory", e);
            return infos;
        }

        // Sort by zipping the two arrays into Integer indices, sorting those, then rebuilding.
        Integer[] order = new Integer[infos.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Comparator<Integer> cmp = buildSortComparator(mode, infos, downloadedAt);
        java.util.Arrays.sort(order, cmp);
        List<GalleryInfo> sorted = new ArrayList<>(infos.size());
        for (Integer idx : order) {
            sorted.add(infos.get(idx));
        }
        return sorted;
    }

    private static Comparator<Integer> buildSortComparator(@NonNull SortMode mode,
            @NonNull List<GalleryInfo> infos, @NonNull List<Long> downloadedAt) {
        switch (mode) {
            case POSTED_DATE_DESC:
                // posted is a string like "2024-01-15 12:34" — string-desc gives newest first.
                return (a, b) -> {
                    String pa = infos.get(a).posted, pb = infos.get(b).posted;
                    if (pa == null) pa = "";
                    if (pb == null) pb = "";
                    return pb.compareTo(pa);
                };
            case TITLE_ASC:
                return (a, b) -> {
                    String ta = titleOf(infos.get(a));
                    String tb = titleOf(infos.get(b));
                    return ta.compareToIgnoreCase(tb);
                };
            case CATEGORY:
                return (a, b) -> {
                    int diff = Integer.compare(infos.get(a).category, infos.get(b).category);
                    if (diff != 0) return diff;
                    return titleOf(infos.get(a)).compareToIgnoreCase(titleOf(infos.get(b)));
                };
            case DOWNLOAD_DATE_DESC:
            default:
                return (a, b) -> Long.compare(downloadedAt.get(b), downloadedAt.get(a));
        }
    }

    private static String titleOf(@NonNull GalleryInfo gi) {
        if (gi.title != null) return gi.title;
        if (gi.titleJpn != null) return gi.titleJpn;
        return "";
    }

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

    @NonNull
    private static GalleryTagGroup[] buildTagGroupsFromList(@Nullable List<String> tgList) {
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

    /**
     * If the locally-stored info lacks tags, fetch the gallery detail in the background and
     * rewrite metadata.json so subsequent opens are fully offline. No-op if tags are already
     * present or SMB is not configured.
     */
    public static void enrichLocalMetadataIfMissing(@NonNull Context context, @NonNull GalleryInfo info) {
        if (info.tgList != null && !info.tgList.isEmpty()) {
            return;
        }
        if (!isConfigured()) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                SmbFile galleryDir = getGalleryDir(info);
                if (!galleryDir.exists()) {
                    return;
                }
                writeMetadataWithDetail(appContext, galleryDir, info);
            } catch (Throwable e) {
                Log.w(TAG, "Failed to enrich local metadata gid=" + info.gid, e);
            }
        });
    }

    private static String readAll(InputStream is) throws IOException {
        // Byte-buffered read so JSON files round-trip unchanged. The previous readLine()
        // loop silently dropped every line terminator, which is harmless for single-line
        // JSON but corrupts pretty-printed metadata blobs and any future caller that
        // expects the file's exact contents.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        try {
            IOUtils.copy(in, out);
        } finally {
            IOUtils.closeQuietly(in);
            IOUtils.closeQuietly(out);
        }
    }
}
