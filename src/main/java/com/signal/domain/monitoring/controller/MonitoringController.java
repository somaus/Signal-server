package com.signal.domain.monitoring.controller;

import com.signal.domain.monitoring.dto.response.DetectionPageResponse;
import com.signal.domain.monitoring.dto.response.MonitoringCreateResponse;
import com.signal.domain.monitoring.dto.response.MonitoringResponse;
import com.signal.domain.monitoring.entity.Detection;
import com.signal.domain.monitoring.entity.Monitoring;
import com.signal.domain.monitoring.service.MonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/monitorings")
@RequiredArgsConstructor
public class MonitoringController {

    private final MonitoringService monitoringService;

    @Operation(summary = "얼굴 도용 모니터링 시작",
            description = "기준 이미지는 웹 역이미지 검색을 위해 외부 검색 서비스(Google Cloud Vision)로 전송되며, 하루 1회 재검색합니다. "
                    + "사용자당 최대 3개. MONITORING_MODE=stub(기본)이면 실제 검색 없이 시연용 예시 결과가 생성됩니다.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MonitoringCreateResponse> createMonitoring(
            @AuthenticationPrincipal Long userId,
            @RequestPart("referenceImage") MultipartFile referenceImage) {
        Monitoring monitoring = monitoringService.createMonitoring(userId, referenceImage);
        return ResponseEntity.status(HttpStatus.CREATED).body(MonitoringCreateResponse.from(monitoring));
    }

    @Operation(summary = "모니터링 상태 조회",
            description = "status(ACTIVE/PAUSED), 마지막 검색 시각, 다음 검색 예정 시각. PAUSED는 검색이 연속으로 실패해 멈춘 상태입니다.")
    @GetMapping("/{monitoringId}")
    public ResponseEntity<MonitoringResponse> getMonitoring(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long monitoringId) {
        return ResponseEntity.ok(MonitoringResponse.from(monitoringService.getMyMonitoring(userId, monitoringId)));
    }

    @Operation(summary = "탐지 결과 조회",
            description = "MONITORING_MODE=search 이면 기준 이미지와 같은 이미지가 실린 웹 페이지(이미지 일치 기준, 얼굴 동일인 판정 아님)이며 matchType(FULL 동일 이미지 / PARTIAL 일부 일치 / PAGE 같은 페이지에 노출)으로 구분합니다. 점수가 아니므로 퍼센트로 표시하지 마세요. thumbnailUrl 은 내리지 않습니다. "
                    + "stub 이면 simulated=true 시연용 예시(sourceUrl 은 null, thumbnailUrl 은 SAMPLE 이미지)입니다.")
    @GetMapping("/{monitoringId}/detections")
    public ResponseEntity<DetectionPageResponse> getDetections(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long monitoringId,
            Pageable pageable) {
        Page<Detection> detections = monitoringService.getDetections(userId, monitoringId, pageable);
        return ResponseEntity.ok(DetectionPageResponse.from(detections));
    }

    @DeleteMapping("/{monitoringId}")
    public ResponseEntity<Void> deleteMonitoring(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long monitoringId) {
        monitoringService.deleteMonitoring(userId, monitoringId);
        return ResponseEntity.noContent().build();
    }
}
