package com.signal.domain.websearch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WebSearchRetentionTest {

    @Test
    void 보존_기간_이전_기록을_삭제한다() {
        WebSearchService service = mock(WebSearchService.class);
        LocalDateTime before = LocalDateTime.now().minusDays(7);

        new WebSearchRetention(service, 7).purge();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(service).purgeOlderThan(cutoff.capture());
        assertThat(cutoff.getValue()).isBetween(before, LocalDateTime.now().minusDays(7));
    }
}
