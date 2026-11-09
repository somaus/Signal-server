package com.signal.domain.chat.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.signal.domain.chat.engine.SituationType;
import com.signal.domain.chat.progress.ConsultationStage;
import com.signal.domain.chat.progress.StageSnapshot;
import com.signal.domain.chat.progress.StageStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StageSnapshotConverterTest {

    private final StageSnapshotConverter converter = new StageSnapshotConverter();

    @Test
    void 직렬화했다가_되돌리면_같은_스냅샷이다() {
        StageSnapshot snapshot = new StageSnapshot(SituationType.IMAGE_ABUSE, Map.of(
                ConsultationStage.SITUATION_CHECK, StageStatus.COMPLETED,
                ConsultationStage.PLATFORM_REPORT, StageStatus.IN_PROGRESS));

        String json = converter.convertToDatabaseColumn(snapshot);

        assertThat(converter.convertToEntityAttribute(json)).isEqualTo(snapshot);
    }

    @Test
    void null과_빈_값은_null로_다룬다() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("  ")).isNull();
    }

    @Test
    void 깨진_JSON은_IllegalStateException이다() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("{not json"))
                .isInstanceOf(IllegalStateException.class);
    }
}
