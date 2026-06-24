package org.airsonic.player.service.cue;

import org.digitalmediaserver.cuelib.CueSheet;
import org.digitalmediaserver.cuelib.FileData;
import org.digitalmediaserver.cuelib.Index;
import org.digitalmediaserver.cuelib.Position;
import org.digitalmediaserver.cuelib.TrackData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A lenient CUE sheet parser that handles extended and non-standard CUE sheets.
 * <p>
 * Unlike the strict cuelib-core parser, this parser:
 * <ul>
 *   <li>Accepts fields longer than 80 characters (CD-TEXT limit is ignored)</li>
 *   <li>Supports time codes with more than 99 minutes</li>
 *   <li>Handles paths with special characters (Unicode, brackets, etc.)</li>
 *   <li>Accepts non-zero first index positions</li>
 *   <li>Parses REM lines for ReplayGain and other metadata</li>
 *   <li>Skips empty lines silently</li>
 *   <li>Accepts non-standard CATALOG and ISRC formats</li>
 * </ul>
 * <p>
 * Produces the same {@link CueSheet} / {@link TrackData} / {@link FileData} /
 * {@link Position} / {@link Index} model objects as cuelib-core for drop-in
 * compatibility.
 */
public class CueParser {

    private static final Logger LOG = LoggerFactory.getLogger(CueParser.class);

    // ── regex patterns ──────────────────────────────────────────────────────

    private static final Pattern PATTERN_QUOTED_STRING = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern PATTERN_POSITION = Pattern.compile("(\\d{1,4}):(\\d{2}):(\\d{2})");
    private static final Pattern PATTERN_FILE = Pattern.compile("^FILE\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_TRACK = Pattern.compile("^TRACK\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_INDEX = Pattern.compile("^INDEX\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_PERFORMER = Pattern.compile("^PERFORMER\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_TITLE = Pattern.compile("^TITLE\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_SONGWRITER = Pattern.compile("^SONGWRITER\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_CATALOG = Pattern.compile("^CATALOG\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_ISRC = Pattern.compile("^ISRC\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_FLAGS = Pattern.compile("^FLAGS\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_PREGAP = Pattern.compile("^PREGAP\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_POSTGAP = Pattern.compile("^POSTGAP\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_CDTEXTFILE = Pattern.compile("^CDTEXTFILE\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_REM = Pattern.compile("^REM\\s", Pattern.CASE_INSENSITIVE);

    private static final Set<String> COMPLIANT_FILE_TYPES = Set.of(
            "BINARY", "MOTOROLA", "AIFF", "WAVE", "MP3", "AAC", "FLAC", "OGG");

    private static final Set<String> COMPLIANT_DATA_TYPES = Set.of(
            "AUDIO", "CDG", "MODE1/2048", "MODE1/2352", "MODE2/2336",
            "MODE2/2352", "CDI/2336", "CDI/2352");

    private static final Set<String> COMPLIANT_FLAGS = Set.of(
            "DCP", "4CH", "PRE", "SCMS", "DATA");

    // ── parser state ────────────────────────────────────────────────────────

    private CueSheet cueSheet;
    private FileData currentFileData;
    private TrackData currentTrackData;
    private int lineNumber;

    // ── public API ──────────────────────────────────────────────────────────

    /**
     * Parse a CUE sheet from an InputStream with the given charset.
     *
     * @param inputStream the input stream to read from
     * @param charset     the character set to use for decoding
     * @return the parsed CueSheet, or null if parsing failed
     * @throws IOException if an I/O error occurs
     */
    public static CueSheet parse(InputStream inputStream, Charset charset) throws IOException {
        CueParser parser = new CueParser();
        return parser.doParse(inputStream, charset);
    }

    /**
     * Parse a CUE sheet from a file Path with the given charset.
     * Opens its own stream so callers don't need to manage stream state.
     *
     * @param cueFile the path to the CUE file
     * @param charset the character set to use for decoding
     * @return the parsed CueSheet, or null if parsing failed
     * @throws IOException if an I/O error occurs
     */
    public static CueSheet parse(Path cueFile, Charset charset) throws IOException {
        try (FileInputStream fis = new FileInputStream(cueFile.toFile());
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            return parse(bis, charset);
        }
    }

    // ── parsing logic ───────────────────────────────────────────────────────

    private CueSheet doParse(InputStream inputStream, Charset charset) throws IOException {
        cueSheet = new CueSheet();
        currentFileData = null;
        currentTrackData = null;
        lineNumber = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, charset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                parseLine(line);
            }
        }

        if (cueSheet.getFileData().isEmpty()) {
            return null;
        }

        return cueSheet;
    }

    private void parseLine(String line) {
        String trimmed = line.trim();

        // skip empty lines silently
        if (trimmed.isEmpty()) {
            return;
        }

        // determine line type and delegate
        if (matches(trimmed, PATTERN_REM)) {
            parseRem(trimmed);
        } else if (matches(trimmed, PATTERN_FILE)) {
            parseFile(trimmed);
        } else if (matches(trimmed, PATTERN_TRACK)) {
            parseTrack(trimmed);
        } else if (matches(trimmed, PATTERN_INDEX)) {
            parseIndex(trimmed);
        } else if (matches(trimmed, PATTERN_PERFORMER)) {
            parsePerformer(trimmed);
        } else if (matches(trimmed, PATTERN_TITLE)) {
            parseTitle(trimmed);
        } else if (matches(trimmed, PATTERN_SONGWRITER)) {
            parseSongwriter(trimmed);
        } else if (matches(trimmed, PATTERN_CATALOG)) {
            parseCatalog(trimmed);
        } else if (matches(trimmed, PATTERN_ISRC)) {
            parseIsrc(trimmed);
        } else if (matches(trimmed, PATTERN_FLAGS)) {
            parseFlags(trimmed);
        } else if (matches(trimmed, PATTERN_PREGAP)) {
            parsePregap(trimmed);
        } else if (matches(trimmed, PATTERN_POSTGAP)) {
            parsePostgap(trimmed);
        } else if (matches(trimmed, PATTERN_CDTEXTFILE)) {
            parseCdTextFile(trimmed);
        } else {
            // Unrecognized line — log at debug level and skip (lenient)
            LOG.debug("Skipping unrecognized CUE line {}: {}", lineNumber, trimmed);
        }
    }

    private boolean matches(String line, Pattern pattern) {
        return pattern.matcher(line).find();
    }

    // ── line parsers ────────────────────────────────────────────────────────

    private void parseFile(String line) {
        // Format: FILE "<path>" <type>
        String filePath = extractQuotedString(line);
        if (filePath == null) {
            LOG.debug("Could not extract file path from line {}: {}", lineNumber, line);
            return;
        }
        String fileType = line.substring(line.lastIndexOf('"') + 1).trim();
        if (fileType.isEmpty()) {
            fileType = "WAVE"; // default
        }

        // Log a debug message for non-compliant file types but accept them
        if (!COMPLIANT_FILE_TYPES.contains(fileType.toUpperCase())) {
            LOG.debug("Non-compliant file type '{}' on line {}, accepting anyway", fileType, lineNumber);
        }

        currentFileData = new FileData(cueSheet, filePath, fileType);
        cueSheet.getFileData().add(currentFileData);
    }

    private void parseTrack(String line) {
        // Format: TRACK <number> <type>
        if (currentFileData == null) {
            LOG.debug("TRACK without preceding FILE on line {}", lineNumber);
            return;
        }

        String[] parts = line.trim().split("\\s+", 3);
        if (parts.length < 3) {
            LOG.debug("Malformed TRACK line {}: {}", lineNumber, line);
            return;
        }

        int trackNumber;
        try {
            trackNumber = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            LOG.debug("Invalid track number on line {}: {}", lineNumber, parts[1]);
            return;
        }

        // Skip negative track numbers
        if (trackNumber < 0) {
            LOG.debug("Skipping negative track number on line {}: {}", lineNumber, trackNumber);
            // Create a sink TrackData to absorb subsequent track-level metadata lines
            // (TITLE, PERFORMER, INDEX, etc.) so they don't incorrectly overwrite
            // album-level metadata. This sink is NOT added to the file's track list.
            String dataType = parts[2].toUpperCase();
            currentTrackData = new TrackData(currentFileData, trackNumber, dataType);
            return;
        }

        String dataType = parts[2].toUpperCase();
        if (!COMPLIANT_DATA_TYPES.contains(dataType)) {
            LOG.debug("Non-compliant data type '{}' on line {}, accepting anyway", dataType, lineNumber);
        }

        currentTrackData = new TrackData(currentFileData, trackNumber, dataType);
        currentFileData.getTrackData().add(currentTrackData);
    }

    private void parseIndex(String line) {
        // Format: INDEX <number> <position>
        if (currentTrackData == null) {
            LOG.debug("INDEX without preceding TRACK on line {}", lineNumber);
            return;
        }

        String[] parts = line.trim().split("\\s+", 3);
        if (parts.length < 3) {
            LOG.debug("Malformed INDEX line {}: {}", lineNumber, line);
            return;
        }

        int indexNumber;
        try {
            indexNumber = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            LOG.debug("Invalid index number on line {}: {}", lineNumber, parts[1]);
            return;
        }

        Position position = parsePosition(parts[2]);
        if (position == null) {
            LOG.debug("Invalid position on line {}: {}", lineNumber, parts[2]);
            return;
        }

        Index index = new Index(indexNumber, position);
        currentTrackData.getIndices().add(index);
    }

    private void parsePerformer(String line) {
        String value = extractQuotedString(line);
        if (value == null) return;

        // Track-level performer
        if (currentTrackData != null) {
            currentTrackData.setPerformer(value);
        } else {
            // Album-level performer
            cueSheet.setPerformer(value);
        }
    }

    private void parseTitle(String line) {
        String value = extractQuotedString(line);
        if (value == null) return;

        // Track-level title
        if (currentTrackData != null) {
            currentTrackData.setTitle(value);
        } else {
            // Album-level title
            cueSheet.setTitle(value);
        }
    }

    private void parseSongwriter(String line) {
        String value = extractQuotedString(line);
        if (value == null) return;

        if (currentTrackData != null) {
            currentTrackData.setSongwriter(value);
        } else {
            cueSheet.setSongwriter(value);
        }
    }

    private void parseCatalog(String line) {
        // Lenient: accept any catalog number
        String value = line.substring("CATALOG".length()).trim();
        // Remove surrounding quotes if present
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (!value.isEmpty()) {
            cueSheet.setCatalog(value);
        }
    }

    private void parseIsrc(String line) {
        // Lenient: accept any ISRC-like code
        String value = line.substring("ISRC".length()).trim();
        if (!value.isEmpty()) {
            if (currentTrackData != null) {
                currentTrackData.setIsrcCode(value);
            }
            // ISRC at album level is non-standard but we accept it
        }
    }

    private void parseFlags(String line) {
        String flagsPart = line.substring("FLAGS".length()).trim();
        if (flagsPart.isEmpty()) return;

        String[] flagTokens = flagsPart.split("\\s+");
        for (String token : flagTokens) {
            if (!COMPLIANT_FLAGS.contains(token.toUpperCase())) {
                LOG.debug("Non-compliant flag '{}' on line {}, accepting anyway", token, lineNumber);
            }
            if (currentTrackData != null) {
                currentTrackData.getFlags().add(token.toUpperCase());
            }
        }
    }

    private void parsePregap(String line) {
        String posStr = line.substring("PREGAP".length()).trim();
        Position position = parsePosition(posStr);
        if (position != null && currentTrackData != null) {
            currentTrackData.setPregap(position);
        }
    }

    private void parsePostgap(String line) {
        String posStr = line.substring("POSTGAP".length()).trim();
        Position position = parsePosition(posStr);
        if (position != null && currentTrackData != null) {
            currentTrackData.setPostgap(position);
        }
    }

    private void parseCdTextFile(String line) {
        String value = extractQuotedString(line);
        if (value != null) {
            cueSheet.setCdTextFile(value);
        }
    }

    private void parseRem(String line) {
        // Format: REM <keyword> <value>
        String remPart = line.substring("REM".length()).trim();
        int spaceIdx = remPart.indexOf(' ');
        if (spaceIdx < 0) {
            // Single token REM (e.g., "REM DISCID")
            // Store as a generic comment
            return;
        }

        String keyword = remPart.substring(0, spaceIdx).trim().toUpperCase();
        String value = remPart.substring(spaceIdx + 1).trim();

        // Remove surrounding quotes if present
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }

        switch (keyword) {
            case "DATE":
                try {
                    cueSheet.setYear(Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    LOG.debug("Invalid year in REM DATE on line {}: {}", lineNumber, value);
                }
                break;
            case "GENRE":
                cueSheet.setGenre(value);
                break;
            case "COMMENT":
                cueSheet.setComment(value);
                break;
            case "DISCID":
                cueSheet.setDiscid(value);
                break;
            case "DISCNUMBER":
                try {
                    cueSheet.setDiscNumber(Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    LOG.debug("Invalid disc number on line {}: {}", lineNumber, value);
                }
                break;
            case "TOTALDISCS":
                try {
                    cueSheet.setTotalDiscs(Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    LOG.debug("Invalid total discs on line {}: {}", lineNumber, value);
                }
                break;
            case "REPLAYGAIN_TRACK_GAIN":
            case "REPLAYGAIN_ALBUM_GAIN":
            case "REPLAYGAIN_TRACK_PEAK":
            case "REPLAYGAIN_ALBUM_PEAK":
                // ReplayGain info — store as a generic comment for now.
                // These are REM lines that don't have dedicated fields in CueSheet.
                // They're logged at debug level and skipped.
                LOG.debug("ReplayGain REM line {}: {} = {}", lineNumber, keyword, value);
                break;
            default:
                // Unknown REM keyword — skip leniently
                LOG.debug("Unknown REM keyword on line {}: {} = {}", lineNumber, keyword, value);
                break;
        }
    }

    // ── helper methods ──────────────────────────────────────────────────────

    /**
     * Extract the first double-quoted string from a line.
     */
    private String extractQuotedString(String line) {
        Matcher m = PATTERN_QUOTED_STRING.matcher(line);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * Parse a position string in format MM:SS:FF. Accepts any number of
     * digits for minutes (lenient).
     */
    private Position parsePosition(String posStr) {
        Matcher m = PATTERN_POSITION.matcher(posStr);
        if (m.find()) {
            int minutes = Integer.parseInt(m.group(1));
            int seconds = Integer.parseInt(m.group(2));
            int frames = Integer.parseInt(m.group(3));
            // Validate seconds (0-59) and frames (0-74)
            if (seconds > 59) {
                LOG.debug("Invalid seconds value {} in position {}, capping to 59", seconds, posStr);
                seconds = 59;
            }
            if (frames > 74) {
                LOG.debug("Invalid frames value {} in position {}, capping to 74", frames, posStr);
                frames = 74;
            }
            return new Position(minutes, seconds, frames);
        }
        return null;
    }
}
