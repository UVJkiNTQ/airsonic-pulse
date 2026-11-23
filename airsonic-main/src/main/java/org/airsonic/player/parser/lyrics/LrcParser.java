/*
 This file is part of Airsonic.

 Airsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Airsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Airsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2025 (C) Y.Tory
 */
package org.airsonic.player.parser.lyrics;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Nullable;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LrcParser {

    private static final Logger LOG = LoggerFactory.getLogger(LrcParser.class);
    private static final Pattern LEADING_TIMESTAMPS = Pattern.compile("^((?:\\[\\d{1,3}:\\d{2}(?:\\.\\d{1,3})?\\])+)(.*)$");
    private static final Pattern SINGLE_TIMESTAMP = Pattern.compile("\\[(\\d{1,3}:\\d{2}(?:\\.\\d{1,3})?)\\]");

    /**
     * Parses a LRC file and returns a list of LyricsLine objects.
     *
     * @param lrcFilePath The path to the LRC file.
     * @return A list of LyricsLine objects parsed from the LRC file.
     * @throws IOException If an error occurs while reading the file.
     */
    @Nullable
    public LrcFile parse(String lrcFilePath) {
        try {
            if (StringUtils.isBlank(lrcFilePath)) {
                LOG.warn("LRC file path is blank");
                return null;
            }
            Path path = Path.of(lrcFilePath);
            return parse(path);
        } catch (InvalidPathException e) {
            LOG.error("Invalid LRC file path: {}", lrcFilePath, e);
            return null;
        }
    }

    /**
     * Parses a LRC file and returns a list of LyricsLine objects.
     *
     * @param lrcFilePath The path to the LRC file.
     * @return A list of LyricsLine objects parsed from the LRC file.
     */
    public LrcFile parse(Path lrcFilePath) {
        if (lrcFilePath == null || !lrcFilePath.toFile().exists()) {
            LOG.warn("LRC file does not exist: {}", lrcFilePath);
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(lrcFilePath.toFile()))) {
            List<String> rawLines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                rawLines.add(line);
            }
            return parseLines(rawLines, Files.getLastModifiedTime(lrcFilePath).toInstant().truncatedTo(ChronoUnit.MICROS));
        } catch (IOException e) {
            LOG.warn("Error reading LRC file: {}", lrcFilePath);
            return null;
        } catch (Exception e) {
            LOG.warn("Unexpected error while parsing LRC file: {}", lrcFilePath);
            return null;
        }
    }

    /**
     * Parses LRC content that did not come from a file, such as lyrics fetched from an external
     * provider. The input is treated exactly like a sidecar file: the same tag handling and
     * punctuation rules apply.
     *
     * @param content the LRC text
     * @return the parsed file, or null when the content is blank or cannot be parsed
     */
    @Nullable
    public LrcFile parseText(@Nullable String content) {
        if (StringUtils.isBlank(content)) {
            LOG.warn("LRC content is blank");
            return null;
        }
        try {
            return parseLines(content.lines().toList(), Instant.now().truncatedTo(ChronoUnit.MICROS));
        } catch (Exception e) {
            LOG.warn("Unexpected error while parsing LRC content");
            return null;
        }
    }

    private LrcFile parseLines(List<String> rawLines, Instant lastModified) {
        List<LyricsLine> lines = new ArrayList<>();
        for (String rawLine : rawLines) {
            if (rawLine == null) {
                continue;
            }
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher lineMatcher = LEADING_TIMESTAMPS.matcher(line);
            if (!lineMatcher.matches()) {
                if (line.matches("^\\[\\d[^\\]]*\\].*")) {
                    // Reject malformed numeric time tags (e.g. [00:40:73]) to match existing LRC validation.
                    LOG.warn("Invalid LRC time tag line: {}", line);
                    throw new IllegalArgumentException("Invalid LRC time tag line: " + line);
                }
                // Skip metadata tags ([ar:...], [by:...]), NetEase JSON credit lines, or untimed text.
                continue;
            }
            String timestampPrefix = lineMatcher.group(1);
            String text = lineMatcher.group(2);
            // Remove A2 Extension inline word timestamps (e.g., <00:12.34>)
            text = text.replaceAll("^\\s*<.*?>", "");
            text = text.replaceAll("\\s<.*?>", "");
            text = text.trim();

            Matcher tagMatcher = SINGLE_TIMESTAMP.matcher(timestampPrefix);
            while (tagMatcher.find()) {
                long time = parseTimeTag(tagMatcher.group(1));
                lines.add(new LyricsLine(time, text));
            }
        }
        return lines.isEmpty() ? null : new LrcFile(lines, lastModified);
    }

    private long parseTimeTag(String tag) {
        // tag format: mm:ss.xxx, mm:ss.xx, mm:ss.x, or mm:ss
        String[] minSec = tag.split(":");
        if (minSec.length != 2) {
            LOG.warn("Invalid LRC time tag format: {}", tag);
            throw new IllegalArgumentException("Invalid LRC time tag format: " + tag);
        }
        int min = Integer.parseInt(minSec[0]);
        String[] secFraction = minSec[1].split("\\.");
        int sec = Integer.parseInt(secFraction[0]);
        int ms = 0;
        if (secFraction.length > 1 && !secFraction[1].isEmpty()) {
            String frac = secFraction[1];
            if (frac.length() == 1) {
                ms = Integer.parseInt(frac) * 100;
            } else if (frac.length() == 2) {
                ms = Integer.parseInt(frac) * 10;
            } else {
                ms = Integer.parseInt(frac.substring(0, 3));
            }
        }
        return min * 60_000L + sec * 1000L + ms;
    }

}
