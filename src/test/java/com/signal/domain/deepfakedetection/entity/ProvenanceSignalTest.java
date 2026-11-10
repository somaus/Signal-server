package com.signal.domain.deepfakedetection.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProvenanceSignalTest {

    @Test
    void 코드로_신호를_찾는다() {
        assertThat(ProvenanceSignal.fromCode("C2PA_MANIFEST")).contains(ProvenanceSignal.C2PA_MANIFEST);
    }

    @Test
    void null이나_모르는_코드는_빈_값이다() {
        assertThat(ProvenanceSignal.fromCode(null)).isEmpty();
        assertThat(ProvenanceSignal.fromCode("REMOVED_SIGNAL")).isEmpty();
        assertThat(ProvenanceSignal.fromCode("c2pa_manifest")).isEmpty();
    }

    @Test
    void 생성_도구와_AI_생성_표시만_강한_근거다() {
        assertThat(ProvenanceSignal.GENERATOR_METADATA.isStrong()).isTrue();
        assertThat(ProvenanceSignal.AI_SOURCE_DECLARED.isStrong()).isTrue();
        assertThat(ProvenanceSignal.AI_EDIT_DECLARED.isStrong()).isFalse();
        assertThat(ProvenanceSignal.C2PA_MANIFEST.isStrong()).isFalse();
    }
}
