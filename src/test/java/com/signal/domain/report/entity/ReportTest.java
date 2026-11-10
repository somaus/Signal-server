package com.signal.domain.report.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReportTest {

    private Report report() {
        return Report.builder().userId(1L).incidentDate(LocalDate.of(2026, 1, 1)).discoveryRoute("SNS")
                .damageType("합성").description("설명").sourceUrls(List.of("https://a")).evidenceIds(List.of(1L))
                .targetAgencyType("POLICE").additionalNotes("메모").build();
    }

    @Test
    void 새로_만들면_DRAFT이고_타임라인에_CREATED가_있다() {
        Report r = report();

        assertThat(r.getStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(r.getTimeline()).extracting(TimelineEntry::getEvent).containsExactly(TimelineEvent.CREATED);
    }

    @Test
    void applyPatch에서_null은_그대로_두고_빈_Optional은_값을_지운다() {
        Report r = report();

        r.applyPatch(null, Optional.empty(), null, null, Optional.empty(), null, null, Optional.of("새 메모"));

        assertThat(r.getIncidentDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(r.getDiscoveryRoute()).isNull();
        assertThat(r.getDamageType()).isEqualTo("합성");
        assertThat(r.getDescription()).isEqualTo("설명");
        assertThat(r.getSourceUrls()).isEmpty();
        assertThat(r.getEvidenceIds()).containsExactly(1L);
        assertThat(r.getTargetAgencyType()).isEqualTo("POLICE");
        assertThat(r.getAdditionalNotes()).isEqualTo("새 메모");
        assertThat(r.getTimeline()).extracting(TimelineEntry::getEvent)
                .containsExactly(TimelineEvent.CREATED, TimelineEvent.UPDATED);
    }

    @Test
    void applyPatch로_받은_목록은_수정_가능한_리스트로_복사한다() {
        Report r = report();

        r.applyPatch(null, null, null, null, Optional.of(List.of("https://b")), Optional.of(List.of(2L)), null,
                null);
        r.getSourceUrls().add("https://c");

        assertThat(r.getSourceUrls()).containsExactly("https://b", "https://c");
        assertThat(r.getEvidenceIds()).containsExactly(2L);
    }

    @Test
    void markFinalized하면_FINALIZED가_되고_타임라인이_남는다() {
        Report r = report();

        r.markFinalized();

        assertThat(r.getStatus()).isEqualTo(ReportStatus.FINALIZED);
        assertThat(r.getTimeline()).extracting(TimelineEntry::getEvent).last().isEqualTo(TimelineEvent.FINALIZED);
    }

    @Test
    void 소유자만_isOwnedBy가_참이다() {
        assertThat(report().isOwnedBy(1L)).isTrue();
        assertThat(report().isOwnedBy(2L)).isFalse();
    }
}
