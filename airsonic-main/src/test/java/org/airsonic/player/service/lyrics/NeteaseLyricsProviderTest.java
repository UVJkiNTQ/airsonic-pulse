package org.airsonic.player.service.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NeteaseLyricsProviderTest {

    private static final String SEARCH_RESULT = """
            {"result":{"songs":[
              {"id":186016,"name":"晴天","duration":270738,"artists":[{"name":"周杰伦"}]},
              {"id":2668397359,"name":"晴天 (原唱 周杰伦)","duration":270738,"artists":[{"name":"RyaVocal"}]},
              {"id":3440441479,"name":"晴天","duration":112373,"artists":[{"name":"Jay"}]}
            ]}}
            """;

    private final NeteaseLyricsProvider provider = new NeteaseLyricsProvider();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void selectBestPrefersMatchingArtistAndDuration() throws Exception {
        JsonNode songs = objectMapper.readTree(SEARCH_RESULT).path("result").path("songs");

        long id = provider.selectBest(songs, "周杰伦", "晴天", 270.7);

        assertThat(id).isEqualTo(186016);
    }

    @Test
    void selectBestRejectsUnrelatedTitles() throws Exception {
        JsonNode songs = objectMapper.readTree(SEARCH_RESULT).path("result").path("songs");

        long id = provider.selectBest(songs, "周杰伦", "七里香", 299.0);

        assertThat(id).isEqualTo(-1);
    }

    @Test
    void selectBestReturnsNoMatchForNullResults() {
        assertThat(provider.selectBest(null, "周杰伦", "晴天", 270.0)).isEqualTo(-1);
    }

    @Test
    void normalizeRemovesVersionSuffixesAndPunctuation() {
        assertThat(NeteaseLyricsProvider.normalize("晴天 (Live)")).isEqualTo("晴天");
        assertThat(NeteaseLyricsProvider.normalize("Song Title feat. Someone")).isEqualTo("songtitle");
        assertThat(NeteaseLyricsProvider.normalize("Hello, World!")).isEqualTo("helloworld");
        assertThat(NeteaseLyricsProvider.normalize(null)).isEmpty();
    }

    @Test
    void scoreRejectsContainedTitles() {
        int contained = NeteaseLyricsProvider.score("周杰伦", "晴天", 270.0, "周杰伦", "晴天娃娃", 270.0);

        assertThat(contained).isZero();
    }

    @Test
    void scoreRewardsArtistAndDuration() {
        int matched = NeteaseLyricsProvider.score("周杰伦", "晴天", 270.7, "周杰伦", "晴天", 270.7);
        int titleOnly = NeteaseLyricsProvider.score("周杰伦", "晴天", 270.7, "Other", "晴天", 180.0);
        int titleAndDuration = NeteaseLyricsProvider.score("周杰伦", "晴天", 270.7, "Other", "晴天", 271.0);
        int differentTitle = NeteaseLyricsProvider.score("周杰伦", "晴天", 270.7, "周杰伦", "七里香", 270.7);

        assertThat(matched).isGreaterThan(titleAndDuration);
        assertThat(titleAndDuration).isGreaterThanOrEqualTo(50);
        assertThat(titleOnly).isEqualTo(25);
        assertThat(differentTitle).isZero();
    }
}
