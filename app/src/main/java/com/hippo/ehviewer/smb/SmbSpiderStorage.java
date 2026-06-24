/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.GallerySpiderStorage;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * {@link GallerySpiderStorage} backed by the SMB share. A thin adapter over the static
 * {@link SmbStorage} helpers so {@code SpiderDen} can talk to one interface instead of reaching
 * into {@code SmbStorage} from a dozen call sites.
 */
public final class SmbSpiderStorage implements GallerySpiderStorage {

    @NonNull
    private final GalleryInfo info;

    private SmbSpiderStorage(@NonNull GalleryInfo info) {
        this.info = info;
    }

    /**
     * Returns an SMB backend for the gallery iff {@code gid} is currently marked as an SMB target,
     * otherwise null (meaning: use phone storage). The mark is re-checked on every call so that
     * clearing it — e.g. when a download is cancelled — immediately reverts to local storage,
     * preserving the behaviour of the old per-call {@code useSmbStorage()} check.
     *
     * <p>{@code gid} and {@code info} are passed separately to mirror {@code SpiderDen}, which
     * tracks a gid independently of the GalleryInfo it hands to the storage helpers.
     */
    @Nullable
    public static SmbSpiderStorage createIfTarget(@NonNull GalleryInfo info, long gid) {
        return SmbStorage.isGidMarkedSmbTarget(gid) ? new SmbSpiderStorage(info) : null;
    }

    @Override
    public boolean prepareDir() {
        return SmbStorage.prepareGalleryDir(info);
    }

    @Nullable
    @Override
    public OutputStream openSpiderInfoOutputStream() {
        return SmbStorage.openSpiderInfoOutputStream(info);
    }

    @Nullable
    @Override
    public InputStream openSpiderInfoInputStream() {
        return SmbStorage.openSpiderInfoInputStream(info);
    }

    @Override
    public boolean containImage(int index) {
        return SmbStorage.containImage(info, index);
    }

    @Override
    public boolean removeImage(int index) {
        return SmbStorage.removeImage(info, index);
    }

    @Nullable
    @Override
    public OutputStreamPipe openImageOutputStreamPipe(int index, @Nullable String extension) {
        return SmbStorage.openSmbOutputStreamPipe(info, index, extension);
    }

    @Nullable
    @Override
    public InputStreamPipe openImageInputStreamPipe(int index) {
        return SmbStorage.openSmbInputStreamPipe(info, index);
    }
}
