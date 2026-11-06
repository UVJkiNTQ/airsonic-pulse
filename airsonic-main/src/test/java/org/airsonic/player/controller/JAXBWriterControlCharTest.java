/*
 This file is part of Airsonic.

 Airsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.
 */
package org.airsonic.player.controller;

import org.airsonic.player.service.VersionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.subsonic.restapi.ArtistID3;
import org.subsonic.restapi.ArtistsID3;
import org.subsonic.restapi.IndexID3;
import org.subsonic.restapi.Response;
import org.subsonic.restapi.ResponseStatus;

import java.util.Map.Entry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link JAXBWriter} sanitizes XML-invalid control characters (e.g. null
 * bytes from mojibake APEv2 tags) in the serialized response. Without this, MOXy writes
 * the raw null byte into the XML, producing malformed output that strict Subsonic clients
 * like Symfonium cannot parse — they hang waiting for a well-formed document.
 */
public class JAXBWriterControlCharTest {

    @Test
    public void xmlResponseContainingNullByte_isSanitized() throws Exception {
        JAXBWriter writer = new JAXBWriter(new VersionService());
        Response resp = buildResponseWithName("Bad\u0000Artist");

        MockHttpServletRequest request = new MockHttpServletRequest();
        Entry<String, String> serialized = writer.serializeForType(request, resp);
        String xml = serialized.getValue();

        // The raw null byte must not leak into the response.
        assertFalse(xml.indexOf('\u0000') >= 0, "raw NUL leaked into XML response");
        // The name survives (with U+FFFD in place of the NUL), so the response is usable.
        assertTrue(xml.contains("Bad\uFFFDArtist"), "name not preserved with U+FFFD substitution");
    }

    @Test
    public void xmlResponseWithNormalText_isUnchanged() throws Exception {
        JAXBWriter writer = new JAXBWriter(new VersionService());
        Response resp = buildResponseWithName("Bon Jovi");

        MockHttpServletRequest request = new MockHttpServletRequest();
        Entry<String, String> serialized = writer.serializeForType(request, resp);
        String xml = serialized.getValue();

        assertTrue(xml.contains("Bon Jovi"), "normal text should pass through untouched");
    }

    @Test
    public void sanitizeInvalidXmlChars_handlesSurrogatePairs() {
        // Emoji (supplementary plane) must survive intact; lone control chars replaced.
        String input = "🎵 \u0000 fine";
        String out = JAXBWriter.sanitizeInvalidXmlChars(input);
        assertTrue(out.contains("🎵"), "surrogate pair (emoji) must survive");
        assertFalse(out.indexOf('\u0000') >= 0, "control char must be replaced");
    }

    private static Response buildResponseWithName(String name) {
        Response resp = new Response();
        resp.setVersion("1.16.1");
        resp.setStatus(ResponseStatus.OK);
        resp.setOpenSubsonic(true);
        ArtistsID3 artists = new ArtistsID3();
        IndexID3 index = new IndexID3();
        index.setName("B");
        ArtistID3 artist = new ArtistID3();
        artist.setId("1");
        artist.setName(name);
        index.getArtist().add(artist);
        artists.getIndex().add(index);
        resp.setArtists(artists);
        return resp;
    }
}
