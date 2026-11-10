package com.signal.domain.protection.dto.response;

import com.signal.domain.protection.entity.Protection;
import com.signal.domain.protection.entity.ProtectionStatus;
import com.signal.global.file.FileUrlSigner;

/**
 * visualDifference는 원본 대비 보호 이미지의 RMS 픽셀 차이(0~1, 실측값)이며 처리 완료 전에는 null이다.
 * simulated는 프론트 호환을 위해 유지하는 필드로, 실제 노이즈 삽입(NoiseImageProtector)을 하므로 항상 false다.
 */
public record ProtectionResponse(
        Long protectionId,
        ProtectionStatus status,
        String originalImageUrl,
        String protectedImageUrl,
        Double visualDifference,
        boolean simulated
) {

    public static ProtectionResponse from(Protection protection, FileUrlSigner signer) {
        return new ProtectionResponse(
                protection.getId(),
                protection.getStatus(),
                signer.sign(protection.getOriginalImageUrl()),
                signer.sign(protection.getProtectedImageUrl()),
                protection.getVisualDifference(),
                false);
    }
}
