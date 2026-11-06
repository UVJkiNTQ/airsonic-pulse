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

 Copyright 2023 (C) Y.Tory
 Copyright 2016 (C) Airsonic Authors
 Based upon Subsonic, Copyright 2009 (C) Sindre Mehus
 */
package org.airsonic.player.service.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.airsonic.player.domain.MediaFile;
import org.airsonic.player.service.MediaFolderService;
import org.airsonic.player.service.SettingsService;
import org.airsonic.player.util.Util;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses meta data from video files using FFmpeg (http://ffmpeg.org/).
 * <p/>
 * Currently duration, bitrate and dimension are supported.
 *
 * @author Sindre Mehus
 */
@Service("ffmpegParser")
@Order(100)
public class FFmpegParser extends MetaDataParser {

    private static final Logger LOG = LoggerFactory.getLogger(FFmpegParser.class);
    private static final String[] FFPROBE_OPTIONS = {
        "-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", "-show_chapters"
    };

    @Autowired
    private MediaFolderService mediaFolderService;

    @Autowired
    private SettingsService settingsService;

    /**
     * Parses meta data for the given music file. No guessing or reformatting is done.
     *
     *
     * @param file The music file to parse.
     * @return Meta data for the file.
     */
    @Override
    public MetaData getRawMetaData(Path file) {

        MetaData metaData = new MetaData();

        try {
            // Use `ffprobe` in the transcode directory if it exists, otherwise let the system sort it out.
            String ffprobe = settingsService.resolveTranscodeExecutable("ffprobe", "ffprobe");

            List<String> command = new ArrayList<>();
            command.add(ffprobe);
            command.addAll(Arrays.asList(FFPROBE_OPTIONS));
            command.add(file.toAbsolutePath().toString());

            Process process = Runtime.getRuntime().exec(command.toArray(new String[0]));
            JsonNode result = null;
            try (InputStream in = process.getInputStream();
                BufferedInputStream bin = new BufferedInputStream(in); ) {
                result = Util.getObjectMapper().readTree(bin);
            } finally {
                process.destroy();
            }

            populateFromJson(result, metaData);

            // Opus base gain lives in the OpusHead header (output_gain), which ffprobe does not
            // expose — read it directly from the container. Opus-only; other formats have no
            // codec-level base gain.
            if ("opus".equalsIgnoreCase(FilenameUtils.getExtension(file.toString()))) {
                metaData.setBaseGain(OpusHeaderReader.readBaseGainDb(file));
            }
        } catch (Throwable x) {
            LOG.warn("Error when parsing metadata in {}", file, x);
        }

        return metaData;
    }

    /**
     * Populates the supplied {@link MetaData} from an already-parsed {@code ffprobe} JSON tree.
     * Factored out of {@link #getRawMetaData} so unit tests can feed synthesized JSON without
     * shelling out to ffprobe — the subprocess invocation is the only thing
     * {@code getRawMetaData} adds on top.
     */
    void populateFromJson(JsonNode result, MetaData metaData) {
        // Only populate duration/bitrate when ffprobe actually produced them. ffprobe emits an empty
        // JSON object ({}) with exit 0 for unparseable input (e.g. junk bytes saved as .mp3), and
        // MissingNode.asDouble()/asInt() silently return 0 — which would make a garbage download look
        // like a valid 0-second file and, in the podcast pipeline, mark the episode COMPLETED instead
        // of ERROR. Leaving them null keeps "could not determine duration" semantics intact.
        JsonNode durationNode = result.at("/format/duration");
        if (!durationNode.isMissingNode() && !durationNode.isNull()) {
            metaData.setDuration(durationNode.asDouble());
        }
        // Bitrate is in Kb/s
        JsonNode bitRateNode = result.at("/format/bit_rate");
        if (!bitRateNode.isMissingNode() && !bitRateNode.isNull()) {
            metaData.setBitRate(bitRateNode.asInt() / 1000);
        }

        // Vorbis comments (FLAC/OGG/Opus) use the no-separator canonical key ALBUMARTIST;
        // ID3v2 TPE2 and MP4 aART are normalized to album_artist by ffprobe. APEv2
        // (WavPack, Musepack, Monkey's Audio) uses "Album artist" with a space, which
        // getData's case variations (lower/upper/Capitalize) can't bridge to the
        // underscore form, so all three aliases are listed explicitly.
        metaData.setAlbumArtist(getDataAny(result, "album_artist", "ALBUMARTIST", "Album artist"));
        metaData.setArtist(getData(result, "artist"));
        metaData.setAlbumName(getData(result, "album"));
        setGenreAndGenres(result, metaData);
        metaData.setTitle(getData(result, "title"));

        // Sort-name trio. ffprobe surfaces ID3v2 sort frames as hyphenated names
        // (TSOT → title-sort, TSOA → album-sort, TSO2 → album_artist_sort) and Vorbis
        // comments case-preserved (TITLESORT etc.); getData covers the case variations.
        metaData.setSortName(getDataAny(result, "title-sort", "TITLESORT", "TSOT"));
        metaData.setAlbumSortName(getDataAny(result, "album-sort", "ALBUMSORT", "TSOA"));
        metaData.setArtistSortName(getDataAny(result, "album-artist-sort", "album_artist_sort", "ALBUMARTISTSORT", "TSO2"));

        metaData.setBpm(parseBpm(getDataAny(result, "TBPM", "BPM")));
        metaData.setCompilation(parseCompilation(getDataAny(result, "compilation", "TCMP")));
        metaData.setDiscSubtitle(getDataAny(result, "DISCSUBTITLE", "TSST"));

        // MusicBrainz IDs — Picard writes spaced "MusicBrainz Album Id" in iTunes-style atoms
        // and TXXX descriptors; Vorbis comments use the underscored uppercase form.
        metaData.setMusicBrainzReleaseId(getDataAny(result,
                "MUSICBRAINZ_ALBUMID", "MusicBrainz Album Id", "musicbrainz_albumid"));
        metaData.setMusicBrainzRecordingId(getDataAny(result,
                "MUSICBRAINZ_TRACKID", "MusicBrainz Track Id", "musicbrainz_trackid"));
        metaData.setMusicBrainzArtistId(getDataAny(result,
                "MUSICBRAINZ_ALBUMARTISTID", "MusicBrainz Album Artist Id", "musicbrainz_albumartistid"));

        // ReplayGain four-field plus Opus R128 fallback for the gains. parseTrackGain/parseAlbumGain
        // prefer REPLAYGAIN_* when present; R128 fires only when RG is null (a present-but-unparseable
        // RG returns null without R128 fallthrough — operator's authored tag wins).
        metaData.setReplayGainTrackGain(parseTrackGain(result));
        metaData.setReplayGainAlbumGain(parseAlbumGain(result));
        metaData.setReplayGainTrackPeak(parseReplayGain(getData(result, RG_TRACK_PEAK)));
        metaData.setReplayGainAlbumPeak(parseReplayGain(getData(result, RG_ALBUM_PEAK)));

        String data = getData(result, "track");
        if (data != null) {
            data = data.replaceFirst("^[\\s\\p{C}]*0+(?!$)", "");
            if (NumberUtils.isCreatable(data)) {
                metaData.setTrackNumber(NumberUtils.createInteger(data));
            }
        }
        data = getData(result, "disc");
        if (data != null) {
            data = data.replaceFirst("^[\\s\\p{C}]*0+(?!$)", "");
            if (NumberUtils.isCreatable(data)) {
                metaData.setDiscNumber(NumberUtils.createInteger(data));
            }
        }

        data = getData(result, "discnumber");
        if (data != null) {
            data = data.replaceFirst("^[\\s\\p{C}]*0+(?!$)", "");
            if (NumberUtils.isCreatable(data)) {
                metaData.setDiscNumber(NumberUtils.createInteger(data));
            }
        }
        // ID3v2 / Vorbis / MP4 normalise to "date"; APEv2 (WavPack, Musepack) uses "Year"
        // (ffprobe preserves the original case). Try both so the same input file yields the
        // same year through either parser.
        data = getDataAny(result, "date", "year");
        metaData.setReleaseDate(data);
        metaData.setYear(parseYear(data));
        // originalReleaseDate fallback order mirrors JaudiotaggerParser: ORIGINALRELEASEDATE
        // (Vorbis) → ORIGINAL_YEAR / TDOR (ID3v2.4) → TORY (ID3v2.3).
        metaData.setOriginalReleaseDate(getDataAny(result,
                "originalreleasedate", "originalyear", "TDOR", "TORY"));

        // Find the first (if any) stream that has dimensions and use those.
        // 'width' and 'height' are display dimensions; compare to 'coded_width', 'coded_height'.
        for (JsonNode stream : result.at("/streams")) {
            JsonNode indexNode = stream.get("index");
            JsonNode codecTypeNode = stream.get("codec_type");
            JsonNode codecNameNode = stream.get("codec_name");
            if (indexNode == null || codecTypeNode == null || codecNameNode == null) {
                LOG.debug("Skipping stream with missing required fields in {}", stream);
                continue;
            }
            Track track = new Track(indexNode.asInt(), codecTypeNode.asText(),
                    stream.at("/tags/language").asText(), codecNameNode.asText());
            metaData.addTrack(track);

            if (track.isVideo() && stream.has("width") && stream.has("height")) {
                metaData.setWidth(stream.get("width").asInt());
                metaData.setHeight(stream.get("height").asInt());
            }
        }
        ObjectMapper mapper = Util.getObjectMapper();
        for (JsonNode chapterJson : result.at("/chapters")) {
            Chapter chapter = mapper.convertValue(chapterJson, Chapter.class);
            metaData.addChapter(chapter);
        }
    }

    /**
     * Returns the track gain in ReplayGain-equivalent dB, preferring the ReplayGain tag when
     * present and falling back to the Opus R128 tag otherwise. A present-but-unparseable RG
     * tag returns {@code null} and does NOT fall through to R128 — the operator's authored
     * tag takes precedence over any inferred R128 value. Mirrors
     * {@link JaudiotaggerParser#parseTrackGain} exactly so clients can't observe drift
     * between the two parsers for the same file.
     */
    Double parseTrackGain(JsonNode node) {
        String rg = getData(node, RG_TRACK_GAIN);
        if (rg != null) {
            return parseReplayGain(rg);
        }
        return parseR128GainQ78(getData(node, R128_TRACK_GAIN));
    }

    /**
     * Returns the album gain in ReplayGain-equivalent dB, preferring the ReplayGain tag when
     * present and falling back to the Opus R128 tag otherwise. Same precedence rule as
     * {@link #parseTrackGain}.
     */
    Double parseAlbumGain(JsonNode node) {
        String rg = getData(node, RG_ALBUM_GAIN);
        if (rg != null) {
            return parseReplayGain(rg);
        }
        return parseR128GainQ78(getData(node, R128_ALBUM_GAIN));
    }

    /**
     * Walks {@code keys} in order, returning the first non-null value from {@link #getData}.
     * Used where a single semantic field has several common physical tag names (e.g. Vorbis
     * upper-snake vs ID3v2 four-letter frame vs MP4 freeform), since {@code getData}'s
     * internal case-variation handles only one base key at a time.
     */
    static String getDataAny(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = getData(node, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Populates both {@link MetaData#setGenre} (scalar) and {@link MetaData#setGenres} (list)
     * from the ffprobe genre tag. APEv2 (WavPack, Musepack, Monkey's Audio) and ID3v2.4 use
     * null-byte ({@code \0}) multi-value separators; Vorbis comments produce multiple
     * same-key instances that ffprobe collapses with null bytes. The scalar genre is the first
     * value; the list carries all values so downstream {@code packGenres} can populate the
     * packed column for multi-frame genre queries.
     */
    static void setGenreAndGenres(JsonNode node, MetaData metaData) {
        String rawGenre = getData(node, "genre");
        if (rawGenre == null || rawGenre.isEmpty()) {
            return;
        }
        // Split on the APEv2 / ID3v2.4 null-byte separator. Use a regex split on the literal
        // \0 character; String.split handles trailing empty tokens correctly.
        List<String> values = Arrays.stream(rawGenre.split("\0", -1))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (values.isEmpty()) {
            return;
        }
        metaData.setGenre(values.get(0));
        metaData.setGenres(values);
    }

    static String getData(JsonNode node, String keyName) {
        // Create a list of key variations to handle different cases
        List<String> keyVariations = ImmutableList.of(
            keyName.toLowerCase(),
            keyName.toUpperCase(),
            StringUtils.capitalize(keyName)  // Capitalizes only the first letter
        );
        // Try to find data in /format/tags/ with different key cases
        for (String key : keyVariations) {
            String path = "/format/tags/" + key;
            String value = node.at(path).asText();
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        // If not found in /format/tags/, check each stream's tags
        if (node.has("streams")) {
            for (JsonNode stream : node.at("/streams")) {
                for (String key : keyVariations) {
                    String tagPath = "/tags/" + key;
                    String value = stream.at(tagPath).asText();
                    if (StringUtils.isNotBlank(value)) {
                        return value;
                    }
                }
            }
        }
        return null;  // Return null if no matching data is found
    }

    /**
     * Not supported.
     */
    @Override
    public void setMetaData(MediaFile file, MetaData metaData) {
        throw new RuntimeException("setMetaData() not supported in " + getClass().getSimpleName());
    }

    /**
     * Returns whether this parser supports tag editing (using the {@link #setMetaData} method).
     *
     * @return Always false.
     */
    @Override
    public boolean isEditingSupported() {
        return false;
    }

    /**
     * Returns whether this parser is applicable to the given file.
     *
     * @param path The path to file in question.
     * @return Whether this parser is applicable to the given file.
     */
    @Override
    public boolean isApplicable(Path path) {
        return Files.isRegularFile(path);
    }

    @Override
    MediaFolderService getMediaFolderService() {
        return mediaFolderService;
    }
}
