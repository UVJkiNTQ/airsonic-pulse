package org.airsonic.player.service.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.Nullable;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Fetches synced LRC lyrics from NetEase Cloud Music (music.163.com).
 *
 * <p>
 * NetEase has no public or supported lyrics API. This uses the same web endpoints the NetEase web
 * player calls: a song search to resolve an id, then the lyric endpoint for that id. Both are
 * called with the NetEase referer, because the lyric endpoint rejects requests without it. The
 * endpoints may change, rate-limit or refuse requests at any time, so every failure degrades to
 * {@code null} and the caller is expected to fall back to another source.
 *
 * <p>
 * Matching is intentionally conservative: the normalized title must match exactly and the artist
 * is used for disambiguation. The media duration, when known, breaks ties between
 * live/remaster/cover versions.
 */
@Component
public class NeteaseLyricsProvider {

    private static final Logger LOG = LoggerFactory.getLogger(NeteaseLyricsProvider.class);

    private static final String SEARCH_URL = "https://music.163.com/api/search/get";
    private static final String LYRIC_URL = "https://music.163.com/api/song/lyric";
    private static final String REFERER = "https://music.163.com";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final int TIMEOUT_MS = 5_000;
    private static final int SEARCH_LIMIT = 10;
    private static final int MIN_SCORE = 50;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    @PreDestroy
    public void close() throws IOException {
        httpClient.close();
    }

    /**
     * Searches NetEase for the given artist/title and returns the LRC text of the best match.
     *
     * @param artist the artist name, may be null or blank
     * @param title the song title, required
     * @param durationSeconds the media duration in seconds, used to disambiguate versions
     * @return the LRC text, or null when no confident match or no lyrics were found
     */
    @Nullable
    public String fetchLyrics(@Nullable String artist, @Nullable String title, @Nullable Double durationSeconds) {
        String wantedArtist = artist == null ? "" : artist.trim();
        String wantedTitle = title == null ? "" : title.trim();
        if (wantedTitle.isEmpty()) {
            return null;
        }
        try {
            String query = (wantedArtist + " " + wantedTitle).trim();
            JsonNode songs = search(query);
            long songId = selectBest(songs, wantedArtist, wantedTitle, durationSeconds);
            if (songId <= 0) {
                LOG.debug("No NetEase lyrics match for '{}' by '{}'", wantedTitle, wantedArtist);
                return null;
            }
            String lrc = fetchLrc(songId);
            if (lrc == null || lrc.isBlank()) {
                LOG.debug("NetEase returned no lyrics for song id {}", songId);
                return null;
            }
            return lrc;
        } catch (Exception e) {
            LOG.warn("Failed to fetch NetEase lyrics for '{}' by '{}': {}", wantedTitle, wantedArtist, e.toString());
            return null;
        }
    }

    @Nullable
    private JsonNode search(String query) throws IOException {
        String url = SEARCH_URL + "?s=" + encode(query) + "&type=1&limit=" + SEARCH_LIMIT;
        JsonNode songs = getJson(url).path("result").path("songs");
        return songs.isArray() ? songs : null;
    }

    @Nullable
    private String fetchLrc(long songId) throws IOException {
        String url = LYRIC_URL + "?id=" + songId + "&lv=1&kv=1&tv=-1";
        String lyric = getJson(url).path("lrc").path("lyric").asText("");
        return lyric.isBlank() ? null : lyric;
    }

    private JsonNode getJson(String url) throws IOException {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(TIMEOUT_MS)
                .setConnectionRequestTimeout(TIMEOUT_MS)
                .setSocketTimeout(TIMEOUT_MS)
                .build();
        HttpGet request = new HttpGet(url);
        request.setConfig(requestConfig);
        request.setHeader("Referer", REFERER);
        request.setHeader("User-Agent", USER_AGENT);
        request.setHeader("Accept", "application/json");
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            return objectMapper.readTree(body);
        }
    }

    long selectBest(@Nullable JsonNode songs, @Nullable String artist, @Nullable String title,
            @Nullable Double durationSeconds) {
        if (songs == null) {
            return -1;
        }
        long bestId = -1;
        int bestScore = 0;
        for (JsonNode song : songs) {
            int candidateScore = score(artist, title, durationSeconds,
                    joinArtists(song.path("artists")),
                    song.path("name").asText(""),
                    song.path("duration").asLong(0) / 1000.0);
            if (candidateScore > bestScore) {
                bestScore = candidateScore;
                bestId = song.path("id").asLong(-1);
            }
        }
        return bestScore >= MIN_SCORE ? bestId : -1;
    }

    static int score(@Nullable String wantedArtist, @Nullable String wantedTitle, @Nullable Double wantedDuration,
            @Nullable String candidateArtist, @Nullable String candidateTitle, @Nullable Double candidateDuration) {
        String wanted = normalize(wantedTitle);
        String candidate = normalize(candidateTitle);
        if (wanted.isEmpty() || candidate.isEmpty()) {
            return 0;
        }
        int result;
        if (!wanted.equals(candidate)) {
            // Containment is intentionally rejected: a candidate like "晴天娃娃" contains "晴天"
            // and, with a matching artist, would otherwise pass the threshold and persist the
            // wrong song's lyrics. Parenthetical version markers are already stripped by normalize.
            return 0;
        }
        result = 50;
        String wantedBy = normalize(wantedArtist);
        String candidateBy = normalize(candidateArtist);
        if (!wantedBy.isEmpty() && !candidateBy.isEmpty()
                && (wantedBy.equals(candidateBy) || wantedBy.contains(candidateBy) || candidateBy.contains(wantedBy))) {
            result += 30;
        }
        if (wantedDuration != null && wantedDuration > 0 && candidateDuration != null && candidateDuration > 0) {
            double diff = Math.abs(wantedDuration - candidateDuration);
            if (diff <= 2) {
                result += 15;
            } else if (diff <= 5) {
                result += 10;
            } else if (diff <= 10) {
                result += 5;
            } else if (diff > 30) {
                result -= 25;
            }
        }
        return result;
    }

    static String normalize(@Nullable String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[（(\\[【].*?[）)\\]】]", "");
        normalized = normalized.replaceAll("(?i)\\s*(feat|ft|featuring)\\.?\\s+.*$", "");
        normalized = normalized.replaceAll("[\\s\\p{Punct}，。！？、；：·“”‘’《》「」『』【】]", "");
        return normalized.trim();
    }

    private static String joinArtists(JsonNode artists) {
        if (artists == null || !artists.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode artist : artists) {
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(artist.path("name").asText(""));
        }
        return builder.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
