package com.signal.domain.protection.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProtectionTest {

    private Protection protection() {
        return Protection.builder().userId(1L).assessmentId(2L).protectionLevel(ProtectionLevel.NORMAL)
                .originalImageUrl("o.jpg").build();
    }

    @Test
    void 새로_만들면_PROCESSING이고_결과가_비어_있다() {
        Protection p = protection();

        assertThat(p.getStatus()).isEqualTo(ProtectionStatus.PROCESSING);
        assertThat(p.getProtectedImageUrl()).isNull();
        assertThat(p.getVisualDifference()).isNull();
    }

    @Test
    void complete하면_COMPLETED가_되고_결과가_남는다() {
        Protection p = protection();

        p.complete("p.jpg", 0.02);

        assertThat(p.getStatus()).isEqualTo(ProtectionStatus.COMPLETED);
        assertThat(p.getProtectedImageUrl()).isEqualTo("p.jpg");
        assertThat(p.getVisualDifference()).isEqualTo(0.02);
    }

    @Test
    void fail하면_FAILED가_된다() {
        Protection p = protection();

        p.fail();

        assertThat(p.getStatus()).isEqualTo(ProtectionStatus.FAILED);
    }

    @Test
    void 소유자만_isOwnedBy가_참이다() {
        assertThat(protection().isOwnedBy(1L)).isTrue();
        assertThat(protection().isOwnedBy(2L)).isFalse();
    }
}
