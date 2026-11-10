package com.signal.domain.websearch.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.signal.domain.monitoring.entity.MatchType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebSearchTest {

    @Test
    void processing으로_만들면_PROCESSING이고_이유와_결과가_비어_있다() {
        WebSearch ws = WebSearch.processing(1L, "u:1");

        assertThat(ws.getStatus()).isEqualTo(WebSearchStatus.PROCESSING);
        assertThat(ws.getUnavailableReason()).isNull();
        assertThat(ws.getMatches()).isEmpty();
    }

    @Test
    void unavailable로_만들면_이유가_남는다() {
        WebSearch ws = WebSearch.unavailable(1L, "a:x", UnavailableReason.VIDEO);

        assertThat(ws.getStatus()).isEqualTo(WebSearchStatus.UNAVAILABLE);
        assertThat(ws.getUnavailableReason()).isEqualTo(UnavailableReason.VIDEO);
    }

    @Test
    void complete하면_COMPLETED가_되고_결과를_복사해_둔다() {
        WebSearch ws = WebSearch.processing(1L, "u:1");
        List<WebMatch> source = new ArrayList<>(List.of(new WebMatch("https://a", "t", MatchType.values()[0])));

        ws.complete(source);
        source.clear();

        assertThat(ws.getStatus()).isEqualTo(WebSearchStatus.COMPLETED);
        assertThat(ws.getMatches()).hasSize(1);
    }

    @Test
    void fail하면_FAILED가_된다() {
        WebSearch ws = WebSearch.processing(1L, "u:1");

        ws.fail();

        assertThat(ws.getStatus()).isEqualTo(WebSearchStatus.FAILED);
    }
}
