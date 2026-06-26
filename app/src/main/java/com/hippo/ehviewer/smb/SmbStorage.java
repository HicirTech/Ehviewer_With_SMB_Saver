package com.hippo.ehviewer.smb;

import android.content.Context;
import android.os.SystemClock;
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
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.unifile.UniFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class SmbStorage {

    private static final String TAG = "SmbStorage";
    // Package-private: SmbMetadata reads/writes the same metadata.json.
    static final String METADATA_FILE = "metadata.json";
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
        CIFSContext base = baseContext();
        String username = Settings.getSmbUsername();
        if (TextUtils.isEmpty(username)) {
            return base;
        }
        NtlmPasswordAuthenticator authenticator =
                new NtlmPasswordAuthenticator(null, username, Settings.getSmbPassword());
        return base.withCredentials(authenticator);
    }

    // Cached base CIFS context. The default ("auto") path reuses jcifs' SingletonContext so its
    // connection pool stays shared; the "signing disabled" path needs custom config, so it gets its
    // own pooled BaseContext built from a PropertyConfiguration. Rebuilt only when the setting flips.
    private static volatile CIFSContext sBaseContext;
    private static volatile boolean sBaseSigningDisabled;

    @NonNull
    private static CIFSContext baseContext() {
        boolean signingDisabled = Settings.getSmbSigningDisabled();
        CIFSContext base = sBaseContext;
        if (base != null && sBaseSigningDisabled == signingDisabled) {
            return base;
        }
        synchronized (SmbStorage.class) {
            if (sBaseContext == null || sBaseSigningDisabled != signingDisabled) {
                sBaseContext = signingDisabled ? buildNoSigningContext() : SingletonContext.getInstance();
                sBaseSigningDisabled = signingDisabled;
            }
            return sBaseContext;
        }
    }

    @NonNull
    private static CIFSContext buildNoSigningContext() {
        try {
            Properties props = new Properties();
            // jcifs already defaults signingPreferred/signingEnforced to false; setting them keeps
            // that explicit. ipcSigningEnforced defaults to TRUE, so turning it off is the real change
            // — it drops the per-packet HMAC on the control/IPC traffic that signing would otherwise
            // add. Data-share signing beyond this is governed by what the server requires.
            props.setProperty("jcifs.smb.client.signingPreferred", "false");
            props.setProperty("jcifs.smb.client.signingEnforced", "false");
            props.setProperty("jcifs.smb.client.ipcSigningEnforced", "false");
            return new BaseContext(new PropertyConfiguration(props));
        } catch (Throwable e) {
            // CIFSException (or anything) building the custom config: fall back to the default context
            // rather than break SMB entirely.
            Log.e(TAG, "Failed to build no-signing CIFS context; using default", e);
            return SingletonContext.getInstance();
        }
    }

    @NonNull
    private static String buildSmbUrl() {
        return SmbPaths.buildShareUrl(
                Settings.getSmbHost(),
                Settings.getSmbPort(),
                Settings.getSmbShareName(),
                Settings.getSmbSharePath());
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

    // Package-private: SmbMetadata resolves the same per-gallery dir for its writes.
    @NonNull
    static SmbFile getGalleryDir(@NonNull GalleryInfo info) throws IOException {
        CIFSContext cifs = buildContext();
        SmbFile shareRoot = new SmbFile(buildSmbUrl(), cifs);
        if (!shareRoot.exists()) {
            shareRoot.mkdirs();
        }
        SmbFile galleryDir = new SmbFile(shareRoot, SmbPaths.buildGalleryFolderName(info) + "/");
        if (!galleryDir.exists()) {
            galleryDir.mkdirs();
        }
        return galleryDir;
    }

    /**
     * Resolves the per-gallery SmbFile reference WITHOUT touching the share (no {@code exists()},
     * no {@code mkdirs()}). {@link #getGalleryDir} did two existence round-trips (plus a possible
     * mkdirs) on every call, which is pure waste on the read path where the folder already exists —
     * and that cost was paid once per page, per existence probe. Read-only callers use this.
     */
    @NonNull
    private static SmbFile resolveGalleryDir(@NonNull GalleryInfo info) throws IOException {
        CIFSContext cifs = buildContext();
        SmbFile shareRoot = new SmbFile(buildSmbUrl(), cifs);
        return new SmbFile(shareRoot, SmbPaths.buildGalleryFolderName(info) + "/");
    }

    /**
     * Short-lived per-gid snapshot of the gallery folder's file names. {@link #containImage} and
     * {@link #findSmbImageFile} answer "is page N saved?" from this in-memory set instead of doing
     * a {@code getGalleryDir()} + one {@code exists()} per supported extension — i.e. ~7 SMB
     * round-trips — on every single page. That per-page cost is what made opening / scanning a big
     * gallery crawl. One {@code list()} now serves every page check until the TTL lapses or a
     * structural change ({@link #prepareGalleryDir}, {@link #removeImage},
     * {@link #deleteGalleryFolder}, {@link #finalizeDownloadedGallery}) invalidates it.
     */
    private static final long LISTING_CACHE_TTL_MS = 5000L;

    private static final class DirListing {
        final long fetchedAt;
        @NonNull final Set<String> names;

        DirListing(long fetchedAt, @NonNull Set<String> names) {
            this.fetchedAt = fetchedAt;
            this.names = names;
        }
    }

    private static final Map<Long, DirListing> LISTING_CACHE = new ConcurrentHashMap<>();

    @NonNull
    private static Set<String> galleryFilenames(@NonNull GalleryInfo info) {
        DirListing cached = LISTING_CACHE.get(info.gid);
        long now = SystemClock.elapsedRealtime();
        if (cached != null && now - cached.fetchedAt < LISTING_CACHE_TTL_MS) {
            return cached.names;
        }
        Set<String> names = new HashSet<>();
        try {
            String[] list = resolveGalleryDir(info).list();
            if (list != null) {
                Collections.addAll(names, list);
            }
        } catch (Throwable e) {
            // Folder may not exist yet (gallery not saved) — treat as empty, cache the miss so we
            // don't re-probe a missing dir on every page.
        }
        LISTING_CACHE.put(info.gid, new DirListing(now, names));
        return names;
    }

    private static void invalidateListing(long gid) {
        LISTING_CACHE.remove(gid);
    }

    public static boolean prepareGalleryDir(@NonNull GalleryInfo info) {
        try {
            getGalleryDir(info);
            invalidateListing(info.gid);
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
            SmbFile galleryDir = resolveGalleryDir(info);
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
        } finally {
            invalidateListing(info.gid);
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

    @Nullable
    private static SmbFile findSmbImageFile(@NonNull GalleryInfo info, int index) throws IOException {
        Set<String> names = galleryFilenames(info);
        for (String extension : com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            String filename = SpiderDen.generateImageFilename(index, extension);
            if (names.contains(filename)) {
                // Build the single matching file reference; no per-extension exists() round-trips.
                return new SmbFile(resolveGalleryDir(info), filename);
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
        Set<String> names = galleryFilenames(info);
        for (String extension : com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            if (names.contains(SpiderDen.generateImageFilename(index, extension))) {
                return true;
            }
        }
        return false;
    }

    public static boolean removeImage(@NonNull GalleryInfo info, int index) {
        boolean result = false;
        try {
            Set<String> names = galleryFilenames(info);
            SmbFile galleryDir = null;
            for (String extension : com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
                String filename = SpiderDen.generateImageFilename(index, extension);
                if (!names.contains(filename)) {
                    continue;
                }
                if (galleryDir == null) {
                    galleryDir = resolveGalleryDir(info);
                }
                SmbFile file = new SmbFile(galleryDir, filename);
                if (file.exists()) {
                    file.delete();
                    result = true;
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to remove SMB image gid=" + info.gid + ", index=" + index, e);
        } finally {
            invalidateListing(info.gid);
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

        SmbFile galleryDir = new SmbFile(shareRoot, SmbPaths.buildGalleryFolderName(info) + "/");
        if (!galleryDir.exists()) {
            galleryDir.mkdirs();
        }

        copyUniDir(localDir, galleryDir);
        SmbMetadata.writeMetadataWithDetail(context, galleryDir, info);
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
            SmbMetadata.writeMetadataWithDetail(context, galleryDir, info, resolvedPages);
            downloadAndWriteCover(context, galleryDir, info);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to finalize SMB gallery gid=" + info.gid, e);
        } finally {
            // The download just wrote every page; drop the stale listing so a reader opening this
            // gallery right after sees the saved files instead of a pre-download empty snapshot.
            invalidateListing(info.gid);
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

    @NonNull
    public static List<GalleryInfo> loadInventory() {
        return loadInventory(SmbSortMode.DOWNLOAD_DATE_DESC);
    }

    @NonNull
    public static List<GalleryInfo> loadInventory(@NonNull SmbSortMode mode) {
        if (!isConfigured()) {
            return new ArrayList<>();
        }

        // Collect (gallery, metadata.json mtime) entries: the mtime feeds the
        // DOWNLOAD_DATE_DESC ordering and isn't a field on GalleryInfo. Other modes ignore it.
        List<SmbSortMode.Entry> entries = new ArrayList<>();
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
                long mtime;
                try {
                    mtime = metadata.lastModified();
                } catch (Throwable ignored) {
                    mtime = 0L;
                }
                entries.add(new SmbSortMode.Entry(info, mtime));
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to load SMB inventory", e);
            // Return whatever was collected before the failure, in insertion order.
            return toGalleryList(entries);
        }

        Collections.sort(entries, mode.comparator());
        return toGalleryList(entries);
    }

    @NonNull
    private static List<GalleryInfo> toGalleryList(@NonNull List<SmbSortMode.Entry> entries) {
        List<GalleryInfo> out = new ArrayList<>(entries.size());
        for (SmbSortMode.Entry e : entries) {
            out.add(e.info);
        }
        return out;
    }

    /**
     * A gallery folder located on the share but not yet read. {@link #loadInventory} reads every
     * {@code metadata.json} up front before the list can show anything, which is O(folders) SMB
     * round-trips on the first paint. The Local Inventory instead lists these refs once (a single
     * share-root enumeration) and reads each folder's metadata lazily — only for the rows actually
     * scrolled into view (see {@link #readGalleryInfo}).
     *
     * <p>{@link #folderMtime} is the folder's own modification time, which the directory enumeration
     * already carries (no extra round-trip), so it can order the default "recently downloaded first"
     * view without reading a single metadata file. It tracks the last write into the gallery folder,
     * i.e. effectively when the download finished — equivalent to the old {@code metadata.json} mtime
     * for ordering purposes.
     */
    public static final class GalleryRef {
        @NonNull public final String folderName;
        public final long folderMtime;

        public GalleryRef(@NonNull String folderName, long folderMtime) {
            this.folderName = folderName;
            this.folderMtime = folderMtime;
        }
    }

    /**
     * Enumerates the gallery folders on the share in one listing, WITHOUT reading any
     * {@code metadata.json}. Cheap enough to drive the first paint of the Local Inventory; callers
     * read each folder's metadata on demand via {@link #readGalleryInfo}.
     */
    @NonNull
    public static List<GalleryRef> listGalleryRefs() {
        List<GalleryRef> refs = new ArrayList<>();
        if (!isConfigured()) {
            return refs;
        }
        try {
            CIFSContext cifs = buildContext();
            SmbFile shareRoot = new SmbFile(buildSmbUrl(), cifs);
            if (!shareRoot.exists() || !shareRoot.isDirectory()) {
                return refs;
            }
            SmbFile[] children = shareRoot.listFiles();
            if (children == null) {
                return refs;
            }
            for (SmbFile child : children) {
                // type and timestamps are populated by the directory enumeration, so isDirectory()
                // and lastModified() here don't cost extra round-trips.
                if (!child.isDirectory()) {
                    continue;
                }
                String name = child.getName();
                if (name.endsWith("/")) {
                    name = name.substring(0, name.length() - 1);
                }
                long mtime;
                try {
                    mtime = child.lastModified();
                } catch (Throwable ignored) {
                    mtime = 0L;
                }
                refs.add(new GalleryRef(name, mtime));
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to list SMB gallery folders", e);
        }
        return refs;
    }

    /**
     * Reads one gallery folder's {@code metadata.json} into a {@link GalleryInfo}. Returns
     * {@code null} when the folder has no parseable metadata. Safe to call off the main thread, one
     * folder at a time, as rows scroll into view.
     */
    @Nullable
    public static GalleryInfo readGalleryInfo(@NonNull GalleryRef ref) {
        if (!isConfigured()) {
            return null;
        }
        try {
            CIFSContext cifs = buildContext();
            SmbFile shareRoot = new SmbFile(buildSmbUrl(), cifs);
            SmbFile folder = new SmbFile(shareRoot, ref.folderName + "/");
            SmbFile metadata = new SmbFile(folder, METADATA_FILE);
            if (!metadata.exists()) {
                return null;
            }
            String json;
            try (InputStream is = metadata.getInputStream()) {
                json = readAll(is);
            }
            JSONObject object = JSONObject.parseObject(json);
            if (object == null) {
                return null;
            }
            return GalleryInfo.galleryInfoFromJson(object);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to read SMB gallery metadata: " + ref.folderName, e);
            return null;
        }
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
