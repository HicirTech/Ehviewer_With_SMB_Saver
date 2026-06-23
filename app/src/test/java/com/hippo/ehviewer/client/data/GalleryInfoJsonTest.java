/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.client.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Round-trips a {@link GalleryInfo} through {@code toJson()} -> JSON text -> {@code
 * galleryInfoFromJson()}. This is exactly what the SMB layer does when it writes/reads
 * {@code metadata.json}, so the test locks down that the persisted shape survives a save+reload —
 * and guards the fork's null-safety fixes in those two methods. Plain JUnit: fastjson is pure Java
 * and constructing a GalleryInfo runs no Android framework code.
 */
public class GalleryInfoJsonTest {

    private static GalleryInfo roundTrip(GalleryInfo in) {
        String text = in.toJson().toJSONString();
        JSONObject parsed = JSON.parseObject(text);
        return GalleryInfo.galleryInfoFromJson(parsed);
    }

    @Test
    public void roundTrip_preservesAllPersistedFields() {
        GalleryInfo in = new GalleryInfo();
        in.gid = 1234567L;
        in.token = "abcdef0123";
        in.title = "Some Title";
        in.titleJpn = "とある題名";
        in.thumb = "https://example.org/t.jpg";
        in.category = 8;
        in.posted = "2025-06-01 12:34";
        in.uploader = "uploader";
        in.rating = 4.5f;
        in.rated = true;
        in.simpleLanguage = "EN";
        in.simpleTags = new String[]{"language:english", "artist:someone"};
        in.thumbHeight = 400;
        in.thumbWidth = 300;
        in.spanSize = 1;
        in.spanIndex = 2;
        in.spanGroupIndex = 3;
        in.favoriteSlot = 5;
        in.favoriteName = "fav";
        in.tgList = new ArrayList<>(Arrays.asList("language:english", "artist:someone"));
        in.pages = 42;

        GalleryInfo out = roundTrip(in);

        assertEquals(in.gid, out.gid);
        assertEquals(in.token, out.token);
        assertEquals(in.title, out.title);
        assertEquals(in.titleJpn, out.titleJpn);
        assertEquals(in.thumb, out.thumb);
        assertEquals(in.category, out.category);
        assertEquals(in.posted, out.posted);
        assertEquals(in.uploader, out.uploader);
        assertEquals(in.rating, out.rating, 0.0001f);
        assertEquals(in.rated, out.rated);
        assertEquals(in.simpleLanguage, out.simpleLanguage);
        assertArrayEquals(in.simpleTags, out.simpleTags);
        assertEquals(in.thumbHeight, out.thumbHeight);
        assertEquals(in.thumbWidth, out.thumbWidth);
        assertEquals(in.spanSize, out.spanSize);
        assertEquals(in.spanIndex, out.spanIndex);
        assertEquals(in.spanGroupIndex, out.spanGroupIndex);
        assertEquals(in.favoriteSlot, out.favoriteSlot);
        assertEquals(in.favoriteName, out.favoriteName);
        assertEquals(in.tgList, out.tgList);
        assertEquals(in.pages, out.pages);
    }

    @Test
    public void roundTrip_dropsNullEntriesInTgList() {
        // The fork's toJson skips null tags on write; galleryInfoFromJson also filters on read.
        // A list with a null in the middle must come back without it.
        GalleryInfo in = new GalleryInfo();
        in.gid = 1L;
        in.tgList = new ArrayList<>(Arrays.asList("a:b", null, "c:d"));

        GalleryInfo out = roundTrip(in);

        assertEquals(Arrays.asList("a:b", "c:d"), out.tgList);
    }

    @Test
    public void roundTrip_emptyTgListOmittedBecomesNull() {
        // toJson only writes "tgList" when it has at least one non-null entry, so an empty list
        // is dropped entirely and reads back as null rather than an empty list.
        GalleryInfo in = new GalleryInfo();
        in.gid = 1L;
        in.tgList = new ArrayList<>();

        GalleryInfo out = roundTrip(in);

        assertNull(out.tgList);
    }

    @Test
    public void fromJson_missingRatedAndRatingDefaultSafely() {
        // Guards the fork's null-safety fix: an older metadata.json without rated/rating must
        // not NPE and should default to (false, 0f) rather than blowing up on unboxing.
        JSONObject obj = JSON.parseObject("{\"gid\":7,\"title\":\"t\"}");

        GalleryInfo out = GalleryInfo.galleryInfoFromJson(obj);

        assertEquals(7L, out.gid);
        assertFalse(out.rated);
        assertEquals(0f, out.rating, 0.0001f);
    }
}
