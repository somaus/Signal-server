package com.signal.domain.report.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReportEvidenceTest {

    @Test
    void 소유자만_isOwnedBy가_참이다() {
        ReportEvidence e = ReportEvidence.builder().userId(1L).fileUrl("f.png").build();

        assertThat(e.isOwnedBy(1L)).isTrue();
        assertThat(e.isOwnedBy(2L)).isFalse();
        assertThat(e.isOwnedBy(null)).isFalse();
    }
}
