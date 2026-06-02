package org.airsonic.player.service.cue;

import org.digitalmediaserver.cuelib.CueSheet;
import org.digitalmediaserver.cuelib.TrackData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the lenient {@link CueParser}.
 */
public class CueParserTest {

    // ── helper ──────────────────────────────────────────────────────────────

    private CueSheet parse(String cueContent) throws IOException {
        return parse(cueContent, StandardCharsets.UTF_8);
    }

    private CueSheet parse(String cueContent, Charset charset) throws IOException {
        byte[] bytes = cueContent.getBytes(charset);
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            return CueParser.parse(is, charset);
        }
    }

    // ── basic parsing ───────────────────────────────────────────────────────

    @Test
    void testBasicCueSheet() throws Exception {
        String cue = """
            PERFORMER "Test Artist"
            TITLE "Test Album"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                PERFORMER "Performer One"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Track Two"
                PERFORMER "Performer Two"
                INDEX 01 04:01:31
            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet, "CueSheet should not be null");
        assertEquals("Test Artist", sheet.getPerformer());
        assertEquals("Test Album", sheet.getTitle());
        assertNotNull(sheet.getFileData());
        assertEquals(1, sheet.getFileData().size());
        assertEquals("test.wav", sheet.getFileData().get(0).getFile());

        assertEquals(2, sheet.getAllTrackData().size());
        TrackData track1 = sheet.getAllTrackData().get(0);
        assertEquals(1, track1.getNumber());
        assertEquals("Track One", track1.getTitle());
        assertEquals("Performer One", track1.getPerformer());
        assertEquals(1, track1.getIndices().size());
        assertEquals(0, track1.getIndices().get(0).getPosition().getMinutes());
        assertEquals(0, track1.getIndices().get(0).getPosition().getSeconds());

        TrackData track2 = sheet.getAllTrackData().get(1);
        assertEquals(2, track2.getNumber());
        assertEquals("Track Two", track2.getTitle());
        assertEquals("Performer Two", track2.getPerformer());
    }

    // ── edge case 1: long title (> 80 chars, CD-TEXT limit) ─────────────────

    @Test
    void testLongTitle() throws Exception {
        String longTitle = "This is a very long album title that would normally exceed the " +
                "eighty character CD-TEXT limit but should be accepted by a lenient parser";
        assertTrue(longTitle.length() > 80, "Test title should exceed 80 chars");

        String cue = """
            PERFORMER "Test Artist"
            TITLE "%s"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                INDEX 01 00:00:00
            """.formatted(longTitle);

        CueSheet sheet = parse(cue);
        assertNotNull(sheet, "CueSheet should not be null despite long title");
        assertEquals(longTitle, sheet.getTitle(), "Long title should be preserved");
        assertEquals(1, sheet.getAllTrackData().size());
    }

    // ── edge case 2: time code > 99 minutes ─────────────────────────────────

    @Test
    void testLongTimeCode() throws Exception {
        String cue = """
            PERFORMER "Test Artist"
            TITLE "Long Recording"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Part One"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Part Two"
                INDEX 01 108:48:69
            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet, "CueSheet should not be null with >99 min time code");
        assertEquals(2, sheet.getAllTrackData().size());

        TrackData track2 = sheet.getAllTrackData().get(1);
        assertEquals(108, track2.getIndices().get(0).getPosition().getMinutes(),
                "Should handle minutes > 99");
        assertEquals(48, track2.getIndices().get(0).getPosition().getSeconds());
        assertEquals(69, track2.getIndices().get(0).getPosition().getFrames());
    }

    // ── edge case 3: non-standard CATALOG ───────────────────────────────────

    @Test
    void testNonStandardCatalog() throws Exception {
        String cue = """
            CATALOG ZMCZ-3325
            PERFORMER "Test Artist"
            TITLE "Catalog Album"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                INDEX 01 00:00:00
            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet, "CueSheet should not be null with non-standard CATALOG");
        assertEquals("ZMCZ-3325", sheet.getCatalog());
    }

    @Test
    void testNumericCatalog() throws Exception {
        String cue = """
            CATALOG 4943674138814
            PERFORMER "Test Artist"
            TITLE "Catalog Album"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                INDEX 01 00:00:00
            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet, "CueSheet should not be null with numeric CATALOG");
        assertEquals("4943674138814", sheet.getCatalog());
    }

    // ── edge case 4: non-standard ISRC ──────────────────────────────────────

    @Test
    void testNonStandardIsrc() throws Exception {
        String cue = """
            PERFORMER "Test Artist"
            TITLE "ISRC Album"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                ISRC JPVI01370323
                INDEX 01 00:00:00
            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet, "CueSheet should not be null with non-standard ISRC");
        assertEquals(1, sheet.getAllTrackData().size());
        assertEquals("JPVI01370323", sheet.getAllTrackData().get(0).getIsrcCode());
    }

    // ── edge case 5: REM REPLAYGAIN lines ───────────────────────────────────

    @Test
    void testReplayGainRemLines() throws Exception {
        String cue = """
            REM GENRE Anime
            REM DATE 2007
            REM DISCID 2903F504
            REM COMMENT "ExactAudioCopy v1.5"
            REM REPLAYGAIN_TRACK_GAIN -10.4 dB
            REM REPLAYGAIN_ALBUM_GAIN -8.2 dB
            REM REPLAYGAIN_TRACK_PEAK 0.891251
            REM REPLAYGAIN_ALBUM_PEAK 0.954993
            PERFORMER "Test Artist"
            TITLE "ReplayGain Album"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                INDEX 01 00:00:00
            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet, "CueSheet should not be null with REPLAYGAIN REM lines");
        assertEquals("Test Artist", sheet.getPerformer());
        assertEquals("ReplayGain Album", sheet.getTitle());
        assertEquals("Anime", sheet.getGenre());
        assertEquals(2007, sheet.getYear());
        assertEquals("2903F504", sheet.getDiscid());
        assertEquals("ExactAudioCopy v1.5", sheet.getComment());
    }

    // ── edge case 6: empty lines ────────────────────────────────────────────

    @Test
    void testEmptyLines() throws Exception {
        String cue = """
            PERFORMER "Test Artist"
            TITLE "Empty Lines Album"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                INDEX 01 00:00:00


            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet, "CueSheet should not be null with trailing empty lines");
        assertEquals(1, sheet.getAllTrackData().size());
    }

    @Test
    void testBlankLinesBetweenTracks() throws Exception {
        String cue = """
            PERFORMER "Test Artist"
            TITLE "Test Album"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                INDEX 01 00:00:00

              TRACK 02 AUDIO
                TITLE "Track Two"
                INDEX 01 04:01:31
            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet, "CueSheet should not be null with blank lines between tracks");
        assertEquals(2, sheet.getAllTrackData().size());
    }

    // ── edge case 7: non-zero first index ───────────────────────────────────

    @Test
    void testNonZeroFirstIndex() throws Exception {
        String cue = """
            PERFORMER "Test Artist"
            TITLE "NonZero Index Album"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                INDEX 02 00:00:00
                INDEX 01 00:03:00
            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet, "CueSheet should not be null with non-zero first index");
        assertEquals(1, sheet.getAllTrackData().size());
        TrackData track = sheet.getAllTrackData().get(0);
        assertEquals(2, track.getIndices().size());
    }

    // ── REM parsing ─────────────────────────────────────────────────────────

    @Test
    void testRemGenre() throws Exception {
        String cue = """
            REM GENRE Rock
            PERFORMER "Test Artist"
            TITLE "Rock Album"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                INDEX 01 00:00:00
            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet);
        assertEquals("Rock", sheet.getGenre());
    }

    @Test
    void testRemDate() throws Exception {
        String cue = """
            REM DATE 1999
            PERFORMER "Test Artist"
            TITLE "Date Album"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                INDEX 01 00:00:00
            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet);
        assertEquals(1999, sheet.getYear());
    }

    @Test
    void testRemDiscid() throws Exception {
        String cue = """
            REM DISCID 2903F504
            PERFORMER "Test Artist"
            TITLE "DiscID Album"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                INDEX 01 00:00:00
            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet);
        assertEquals("2903F504", sheet.getDiscid());
    }

    // ── negative track number ───────────────────────────────────────────────

    @Test
    void testNegativeTrackSkipped() throws Exception {
        String cue = """
            PERFORMER "Test Artist"
            TITLE "Negative Track Album"
            FILE "test.wav" WAVE
              TRACK -1 AUDIO
                TITLE "Invalid Track"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Valid Track"
                INDEX 01 04:01:31
            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet);
        // Track -1 should be skipped, only Track 02 remains
        assertEquals(1, sheet.getAllTrackData().size());
        assertEquals(2, sheet.getAllTrackData().get(0).getNumber());
        assertEquals("Valid Track", sheet.getAllTrackData().get(0).getTitle());
    }

    // ── songwriter ──────────────────────────────────────────────────────────

    @Test
    void testSongwriter() throws Exception {
        String cue = """
            SONGWRITER "Album Songwriter"
            PERFORMER "Test Artist"
            TITLE "Songwriter Album"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                SONGWRITER "Track Songwriter"
                INDEX 01 00:00:00
            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet);
        assertEquals("Album Songwriter", sheet.getSongwriter());
        assertEquals("Track Songwriter", sheet.getAllTrackData().get(0).getSongwriter());
    }

    // ── flags ───────────────────────────────────────────────────────────────

    @Test
    void testFlags() throws Exception {
        String cue = """
            PERFORMER "Test Artist"
            TITLE "Flags Album"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                FLAGS DCP 4CH
                INDEX 01 00:00:00
            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet);
        assertTrue(sheet.getAllTrackData().get(0).getFlags().contains("DCP"));
        assertTrue(sheet.getAllTrackData().get(0).getFlags().contains("4CH"));
    }

    // ── pregap / postgap ───────────────────────────────────────────────────

    @Test
    void testPregap() throws Exception {
        String cue = """
            PERFORMER "Test Artist"
            TITLE "Pregap Album"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                PREGAP 00:02:00
                INDEX 01 00:00:00
            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet);
        assertNotNull(sheet.getAllTrackData().get(0).getPregap());
        assertEquals(0, sheet.getAllTrackData().get(0).getPregap().getMinutes());
        assertEquals(2, sheet.getAllTrackData().get(0).getPregap().getSeconds());
    }

    // ── CDTEXTFILE ──────────────────────────────────────────────────────────

    @Test
    void testCdTextFile() throws Exception {
        String cue = """
            CDTEXTFILE "cdtext.txt"
            PERFORMER "Test Artist"
            TITLE "CDText Album"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                INDEX 01 00:00:00
            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet);
        assertEquals("cdtext.txt", sheet.getCdTextFile());
    }

    // ── multi-file CUE sheets ───────────────────────────────────────────────

    @Test
    void testMultiFileCueSheet() throws Exception {
        String cue = """
            PERFORMER "Test Artist"
            TITLE "Multi File Album"
            FILE "disc1.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                INDEX 01 00:00:00
            FILE "disc2.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track Two"
                INDEX 01 00:00:00
            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet);
        assertEquals(2, sheet.getFileData().size());
        assertEquals("disc1.wav", sheet.getFileData().get(0).getFile());
        assertEquals("disc2.wav", sheet.getFileData().get(1).getFile());
        assertEquals(2, sheet.getAllTrackData().size());
    }

    // ── unrecognized lines ──────────────────────────────────────────────────

    @Test
    void testUnknownRemKeyword() throws Exception {
        String cue = """
            REM UNKNOWN_KEYWORD some value
            PERFORMER "Test Artist"
            TITLE "Unknown REM Album"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                INDEX 01 00:00:00
            """;

        CueSheet sheet = parse(cue);
        assertNotNull(sheet, "CueSheet should not be null with unknown REM keyword");
        assertEquals(1, sheet.getAllTrackData().size());
    }

    // ── empty cue sheet ─────────────────────────────────────────────────────

    @Test
    void testEmptyCueSheet() throws Exception {
        String cue = "";

        CueSheet sheet = parse(cue);
        assertNull(sheet, "Empty CUE sheet should return null");
    }

    @Test
    void testOnlyRemLines() throws Exception {
        String cue = """
            REM GENRE Rock
            REM DATE 2023
            REM DISCID ABCDEF01
            """;

        CueSheet sheet = parse(cue);
        assertNull(sheet, "CUE sheet with only REM lines should return null (no FILE)");
    }

    // ── file parsing from test resources ────────────────────────────────────

    @Test
    void testParseExtendedCueFile(@TempDir Path tempDir) throws Exception {
        // Copy extended cue files and test parsing
        String longTitleCue = """
            REM GENRE Anime
            REM DATE 2007
            REM DISCID 2903F504
            REM COMMENT "ExactAudioCopy v1.5"
            CATALOG ZMCZ-3325
            PERFORMER "Test Artist"
            TITLE "This is a very long album title that would normally exceed the eighty character CD-TEXT limit but should be accepted by a lenient parser"
            FILE "test.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Track One"
                PERFORMER "Test Artist"
                ISRC JPVI01370323
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Track Two"
                PERFORMER "Test Artist"
                INDEX 01 04:01:31
            """;

        Path cueFile = tempDir.resolve("test.cue");
        Files.writeString(cueFile, longTitleCue);

        try (InputStream is = Files.newInputStream(cueFile)) {
            CueSheet sheet = CueParser.parse(is, StandardCharsets.UTF_8);
            assertNotNull(sheet);
            assertEquals(2, sheet.getAllTrackData().size());
            assertEquals("ZMCZ-3325", sheet.getCatalog());
            assertEquals(2007, sheet.getYear());
            assertEquals("Anime", sheet.getGenre());
        }
    }
}
