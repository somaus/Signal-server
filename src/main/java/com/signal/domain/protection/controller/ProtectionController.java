package com.signal.domain.protection.controller;

import com.signal.domain.protection.dto.request.CreateProtectionRequest;
import com.signal.domain.protection.dto.response.ProtectionCreateResponse;
import com.signal.domain.protection.dto.response.ProtectionResponse;
import com.signal.domain.protection.entity.Protection;
import com.signal.domain.protection.service.ProtectionService;
import com.signal.global.file.FileUrlSigner;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/protections")
@RequiredArgsConstructor
public class ProtectionController {

    private final ProtectionService protectionService;
    private final FileUrlSigner fileUrlSigner;

    @Operation(summary = "이미지 보호 처리 요청",
            description = "원본 이미지에 노이즈를 삽입해 보호 이미지를 비동기로 생성합니다. 학습 모델을 겨냥한 적대적 섭동이 아닌 일반 노이즈 삽입이라 도용·합성 방지를 보장하지는 않습니다.")
    @PostMapping
    public ResponseEntity<ProtectionCreateResponse> createProtection(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateProtectionRequest request) {
        Protection protection = protectionService.createProtection(
                userId, request.assessmentId(), request.protectionLevel());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ProtectionCreateResponse.from(protection));
    }

    @Operation(summary = "보호 처리 결과 조회",
            description = "status가 COMPLETED이면 보호 이미지 URL과 실측 visualDifference(원본 대비 RMS 픽셀 차이, 0~1)를 제공합니다.")
    @GetMapping("/{protectionId}")
    public ResponseEntity<ProtectionResponse> getProtection(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long protectionId) {
        Protection protection = protectionService.getProtection(userId, protectionId);
        return ResponseEntity.ok(ProtectionResponse.from(protection, fileUrlSigner));
    }

    @Operation(summary = "보호 이미지 다운로드",
            description = "노이즈가 삽입된 보호 이미지(PNG)를 내려받습니다. 처리 완료 전에는 요청이 거부됩니다.")
    @GetMapping("/{protectionId}/download")
    public ResponseEntity<byte[]> downloadProtectedImage(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long protectionId) {
        byte[] image = protectionService.downloadProtectedImage(userId, protectionId);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(image);
    }
}
