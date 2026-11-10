package com.signal.domain.protection.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.signal.domain.protection.dto.response.ProtectionResponse;
import com.signal.domain.protection.entity.Protection;
import com.signal.domain.protection.entity.ProtectionLevel;
import com.signal.global.file.FileUrlSigner;
import org.junit.jupiter.api.Test;

class ProtectionResponseTest {

    private static final FileUrlSigner SIGNER = new FileUrlSigner("s", 3600);

    @Test
    void 완료된_보호결과는_simulated가_아니고_실측_visualDifference를_노출한다() {
        Protection protection = Protection.builder().userId(1L).assessmentId(1L)
                .originalImageUrl("/uploads/a.png").protectionLevel(ProtectionLevel.NORMAL).build();
        protection.complete("/uploads/protections/p.png", 0.0231);

        ProtectionResponse response = ProtectionResponse.from(protection, SIGNER);

        assertThat(response.simulated()).isFalse();
        assertThat(response.visualDifference()).isEqualTo(0.0231);
        assertThat(response.protectedImageUrl()).startsWith("/uploads/protections/p.png?exp=").contains("&sig=");
        assertThat(response.originalImageUrl()).contains("&sig=");
    }

    @Test
    void 처리중이면_visualDifference는_null이다() {
        Protection protection = Protection.builder().userId(1L).assessmentId(1L)
                .originalImageUrl("/uploads/a.png").protectionLevel(ProtectionLevel.NORMAL).build();

        assertThat(ProtectionResponse.from(protection, SIGNER).visualDifference()).isNull();
    }
}
