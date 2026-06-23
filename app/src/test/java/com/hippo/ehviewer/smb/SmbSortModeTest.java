/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for the Local Inventory ordering policy. These exercise the comparators in isolation
 * — no SMB connection required — which is the whole point of pulling {@link SmbSortMode} out of
 * {@code SmbStorage}.
 *
 * <p>Plain JUnit (no Robolectric): {@link SmbSortMode} only touches {@link GalleryInfo}'s public
 * fields, and constructing a {@code GalleryInfo} runs no Android framework code, so the test needs
 * no emulated SDK.
 */
public class SmbSortModeTest {

    @Test
    public void fromOrdinal_mapsEachConstant() {
        assertSame(SmbSortMode.DOWNLOAD_DATE_DESC, SmbSortMode.fromOrdinal(0));
        assertSame(SmbSortMode.POSTED_DATE_DESC, SmbSortMode.fromOrdinal(1));
        assertSame(SmbSortMode.TITLE_ASC, SmbSortMode.fromOrdinal(2));
        assertSame(SmbSortMode.CATEGORY, SmbSortMode.fromOrdinal(3));
    }

    @Test
    public void fromOrdinal_outOfRangeFallsBackToDefault() {
        assertSame(SmbSortMode.DOWNLOAD_DATE_DESC, SmbSortMode.fromOrdinal(-1));
        assertSame(SmbSortMode.DOWNLOAD_DATE_DESC, SmbSortMode.fromOrdinal(99));
    }

    @Test
    public void downloadDateDesc_newestMtimeFirst() {
        SmbSortMode.Entry older = entry(gallery("a", null, "", 0), 100L);
        SmbSortMode.Entry newer = entry(gallery("b", null, "", 0), 200L);

        List<SmbSortMode.Entry> list = sorted(SmbSortMode.DOWNLOAD_DATE_DESC, older, newer);

        assertEquals("b", list.get(0).info.title);
        assertEquals("a", list.get(1).info.title);
    }

    @Test
    public void postedDateDesc_newestPostedFirst_nullsLast() {
        SmbSortMode.Entry old = entry(gallery("old", null, "2024-01-01 00:00", 0), 0L);
        SmbSortMode.Entry recent = entry(gallery("recent", null, "2025-06-01 00:00", 0), 0L);
        SmbSortMode.Entry undated = entry(gallery("undated", null, null, 0), 0L);

        List<SmbSortMode.Entry> list = sorted(SmbSortMode.POSTED_DATE_DESC, old, undated, recent);

        assertEquals("recent", list.get(0).info.title);
        assertEquals("old", list.get(1).info.title);
        // null posted normalises to "" which sorts last under reverse string order.
        assertEquals("undated", list.get(2).info.title);
    }

    @Test
    public void titleAsc_caseInsensitive_andFallsBackToTitleJpn() {
        SmbSortMode.Entry banana = entry(gallery("banana", null, "", 0), 0L);
        SmbSortMode.Entry apple = entry(gallery("Apple", null, "", 0), 0L);
        // No primary title: should sort by titleJpn ("cherry").
        SmbSortMode.Entry cherry = entry(gallery(null, "cherry", "", 0), 0L);

        List<SmbSortMode.Entry> list = sorted(SmbSortMode.TITLE_ASC, banana, cherry, apple);

        assertEquals("Apple", list.get(0).info.title);
        assertEquals("banana", list.get(1).info.title);
        assertEquals("cherry", list.get(2).info.titleJpn);
    }

    @Test
    public void category_groupsByCategoryThenTitle() {
        SmbSortMode.Entry cat2b = entry(gallery("b", null, "", 2), 0L);
        SmbSortMode.Entry cat1z = entry(gallery("z", null, "", 1), 0L);
        SmbSortMode.Entry cat1a = entry(gallery("a", null, "", 1), 0L);

        List<SmbSortMode.Entry> list = sorted(SmbSortMode.CATEGORY, cat2b, cat1z, cat1a);

        // Category 1 first (a, z by title), then category 2.
        assertEquals("a", list.get(0).info.title);
        assertEquals("z", list.get(1).info.title);
        assertEquals("b", list.get(2).info.title);
    }

    // --- helpers -------------------------------------------------------------------------------

    private static GalleryInfo gallery(String title, String titleJpn, String posted, int category) {
        GalleryInfo gi = new GalleryInfo();
        gi.title = title;
        gi.titleJpn = titleJpn;
        gi.posted = posted;
        gi.category = category;
        return gi;
    }

    private static SmbSortMode.Entry entry(GalleryInfo info, long downloadedAtMillis) {
        return new SmbSortMode.Entry(info, downloadedAtMillis);
    }

    private static List<SmbSortMode.Entry> sorted(SmbSortMode mode, SmbSortMode.Entry... entries) {
        List<SmbSortMode.Entry> list = new ArrayList<>();
        Collections.addAll(list, entries);
        Collections.sort(list, mode.comparator());
        return list;
    }
}
