/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for the share-URL construction extracted into {@link SmbPaths}. The whole reason this
 * logic was pulled out of {@code SmbStorage} is so the share-name encoding (issue #2) can be checked
 * without a live share or Settings — plain JUnit, no Android.
 *
 * <p>{@link SmbPaths#buildGalleryFolderName} is intentionally not covered here: it's a pure function
 * of its argument, but it delegates to {@code FileUtils.sanitizeFilename}, which transitively calls
 * {@code android.text.TextUtils} and therefore can't run under plain JUnit (and the repo's
 * Robolectric can't pick an SDK for targetSdk 30). It's exercised end-to-end by the build instead.
 */
public class SmbPathsTest {

    @Test
    public void shareUrl_defaultPortOmitted() {
        assertEquals("smb://192.168.1.10/media/ehviewer/",
                SmbPaths.buildShareUrl("192.168.1.10", "445", "media", "/ehviewer/"));
    }

    @Test
    public void shareUrl_nonDefaultPortIncluded() {
        assertEquals("smb://192.168.1.10:4450/media/ehviewer/",
                SmbPaths.buildShareUrl("192.168.1.10", "4450", "media", "/ehviewer/"));
    }

    @Test
    public void shareUrl_emptyPortOmitted() {
        assertEquals("smb://host/media/",
                SmbPaths.buildShareUrl("host", "", "media", "/"));
    }

    @Test
    public void shareUrl_spaceInShareEncodedAsPercent20() {
        // URLEncoder would emit "+" for a space; buildShareUrl converts it back to %20 so the
        // result is a valid smb URL rather than form-encoded.
        assertEquals("smb://host/Public%20Documents/",
                SmbPaths.buildShareUrl("host", "445", "Public Documents", "/"));
    }

    @Test
    public void shareUrl_reservedCharInShareEncoded() {
        // '$' is reserved and must be percent-encoded (%24); it must not survive raw.
        assertEquals("smb://host/Family%24/",
                SmbPaths.buildShareUrl("host", "445", "Family$", "/"));
    }

    @Test
    public void shareUrl_emptyShareNotEncoded() {
        assertEquals("smb://host//",
                SmbPaths.buildShareUrl("host", "445", "", "/"));
    }

    @Test
    public void shareUrl_nullHostAndPathTreatedAsEmpty() {
        // Pure helper must not NPE on nulls even though Settings never hands it any.
        assertEquals("smb:///media",
                SmbPaths.buildShareUrl(null, null, "media", null));
    }
}
