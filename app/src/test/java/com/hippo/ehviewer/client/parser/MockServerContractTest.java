/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.client.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.hippo.ehviewer.client.exception.ParseException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Probe: feed the eh-mock-server response formats through EhViewer's real parsers, proving the mock
 * is something the app can actually talk to. Strings here mirror what eh-mock-server emits
 * (src/html.ts, src/api.ts).
 */
// Force a stub Application: the real EhApplication.onCreate registers broadcast receivers and other
// device-only services that blow up on the JVM, and the parser contract here needs none of it.
@Config(manifest = Config.NONE, application = Application.class)
@RunWith(RobolectricTestRunner.class)
public class MockServerContractTest {

  @Test
  public void mockPageHtml_parsesWithGalleryPageParser() throws ParseException {
    String body =
        "<!DOCTYPE html><html><head><title>x</title></head><body>\n"
            + "<div id=\"i3\"><a href=\"http://localhost:8080/s/734c71fcb5/2292074-1\">"
            + "<img id=\"img\" src=\"http://localhost:8080/img/2292074/1\" style=\"width:1280px;height:1810px\" "
            + "onerror=\"this.onerror=null; nl('00000-000000')\" /></a></div>\n"
            + "<div id=\"i6\" class=\"if\"> <a href=\"#\" id=\"loadfail\" onclick=\"return nl('00000-000000')\">x</a> </div>\n"
            + "<script type=\"text/javascript\">var gid=2292074;var startpage=1;var startkey=\"abc\";"
            + "var showkey=\"56d777aac37\";var base_url=\"http://localhost:8080/\";"
            + "var api_url = \"http://localhost:8080/api.php\";</script>\n</body></html>";

    GalleryPageParser.Result r = GalleryPageParser.parse(body);
    assertEquals("http://localhost:8080/img/2292074/1", r.imageUrl);
    assertEquals("56d777aac37", r.showKey);
    assertEquals("00000-000000", r.skipHathKey);
  }

  @Test
  public void mockGtokenJson_parsesWithGalleryTokenApiParser() throws Exception {
    String body = "{\"tokenlist\":[{\"gid\":2292074,\"token\":\"734c71fcb5\"}]}";
    assertEquals("734c71fcb5", GalleryTokenApiParser.parse(body));
  }

  @Test
  public void mockShowpageJson_parsesWithGalleryPageApiParser() throws ParseException {
    String body =
        "{\"p\":1,\"s\":\"s/734c71fcb5/2292074-1\","
            + "\"i3\":\"<a href=\\\"#\\\"><img id=\\\"img\\\" src=\\\"http://localhost:8080/img/2292074/1\\\" "
            + "style=\\\"height:1810px;width:1280px\\\" onerror=\\\"this.onerror=null; nl('00000-000000')\\\" /></a>\","
            + "\"i5\":\"\",\"i6\":\" <a href=\\\"#\\\" id=\\\"loadfail\\\" onclick=\\\"return nl('00000-000000')\\\">x</a> \","
            + "\"i7\":\"\",\"k\":\"734c71fcb5\"}";

    GalleryPageApiParser.Result r = GalleryPageApiParser.parse(body);
    assertEquals("http://localhost:8080/img/2292074/1", r.imageUrl);
    assertEquals("00000-000000", r.skipHathKey);
  }

  @Test
  public void robolectricRunsAtSdk28() {
    // Sanity: if this body runs at all, Robolectric initialised (the thing that blocks the rest of
    // the parser suite at targetSdk 30) — and android.text.TextUtils is real, not the "not mocked"
    // stub, which every parser above relies on.
    assertTrue(android.text.TextUtils.isEmpty(""));
  }
}
