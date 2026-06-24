/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.spider;

import androidx.annotation.Nullable;

import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * A storage backend for a single gallery's downloaded pages and spider-info file.
 *
 * <p>This is the seam {@link SpiderDen} routes through when a gallery is not stored on phone
 * storage. It exists so the "where do these bytes live" decision is a single extension point
 * rather than a {@code useSmbStorage()} branch sprinkled through every read/write method: today
 * the only implementation is SMB, but a future WebDAV/FTP backend would just be another
 * implementation selected in one place, with no further changes to {@code SpiderDen}.
 *
 * <p>All methods operate on the gallery the implementation was created for; index parameters are
 * zero-based page indices matching {@link SpiderDen#generateImageFilename}. Implementations do
 * blocking network/disk I/O and must be called from worker threads.
 */
public interface GallerySpiderStorage {

    /** Ensure the gallery's destination directory exists. Returns false on failure. */
    boolean prepareDir();

    /** Open a stream to write the per-gallery spider-info file, or null on failure. */
    @Nullable
    OutputStream openSpiderInfoOutputStream();

    /** Open a stream to read the per-gallery spider-info file, or null if absent. */
    @Nullable
    InputStream openSpiderInfoInputStream();

    /** Whether a page image at {@code index} is already stored. */
    boolean containImage(int index);

    /** Remove the stored page image at {@code index}. Returns true if anything was deleted. */
    boolean removeImage(int index);

    /**
     * Open a pipe to write the page image at {@code index}.
     *
     * @param extension file extension (with or without leading dot), or null to let the backend
     *                  pick a default
     */
    @Nullable
    OutputStreamPipe openImageOutputStreamPipe(int index, @Nullable String extension);

    /** Open a pipe to read the stored page image at {@code index}, or null if absent. */
    @Nullable
    InputStreamPipe openImageInputStreamPipe(int index);
}
