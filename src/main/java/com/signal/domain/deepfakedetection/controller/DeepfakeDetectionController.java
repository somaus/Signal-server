package com.signal.domain.deepfakedetection.controller;

import com.signal.domain.deepfakedetection.dto.response.DeepfakeDetectionCreateResponse;
import com.signal.domain.deepfakedetection.dto.response.DeepfakeDetectionResponse;
import com.signal.domain.deepfakedetection.entity.DeepfakeDetection;
import com.signal.domain.deepfakedetection.service.DeepfakeDetectionService;
import com.signal.domain.deepfakedetection.dto.response.WebSearchResponse;
import com.signal.domain.websearch.entity.WebSearch;
import com.signal.domain.websearch.service.WebSearchService;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/deepfake-detections")
@RequiredArgsConstructor
public class DeepfakeDetectionController {

    private final DeepfakeDetectionService deepfakeDetectionService;
    private final WebSearchService webSearchService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DeepfakeDetectionCreateResponse> createDetection(
            @AuthenticationPrincipal Long userId,
            @RequestHeader(value = "X-Anonymous-Id", required = false) String anonymousId,
            @RequestPart("file") MultipartFile file) {
        DeepfakeDetection detection = deepfakeDetectionService.createDetection(userId, anonymousId, file);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(DeepfakeDetectionCreateResponse.from(detection));
    }

    /**
     * 이 사진을 웹에서 찾아본다. 사용자가 버튼을 눌러 사진이 외부 검색 서비스로 전송되는 데 동의한 뒤에만 부른다.
     * 사진 하나에 한 번만 검색하며, 이미 했으면 그 결과를 돌려준다. 기능이 꺼져 있으면 404.
     */
    @PostMapping("/{detectionId}/web-search")
    public ResponseEntity<WebSearchResponse> startWebSearch(
            @AuthenticationPrincipal Long userId,
            @RequestHeader(value = "X-Anonymous-Id", required = false) String anonymousId,
            @PathVariable Long detectionId) {
        if (!webSearchService.isEnabled()) {
            throw new SignalException(ErrorCode.NOT_FOUND);
        }
        if (userId == null && !StringUtils.hasText(anonymousId)) {
            throw new SignalException(ErrorCode.ANONYMOUS_ID_REQUIRED);
        }
        DeepfakeDetection detection = deepfakeDetectionService.getDetection(userId, anonymousId, detectionId);
        webSearchService.start(detectionId, detection.getFileUrl(), isVideo(detection.getFileUrl()),
                userId != null ? "u:" + userId : "a:" + anonymousId);
        WebSearch webSearch = webSearchService.find(detectionId);
        if (webSearch == null) {
            throw new SignalException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(WebSearchResponse.from(webSearch));
    }

    private static boolean isVideo(String fileUrl) {
        String lower = fileUrl.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mov");
    }

    @GetMapping("/{detectionId}")
    public ResponseEntity<DeepfakeDetectionResponse> getDetection(
            @AuthenticationPrincipal Long userId,
            @RequestHeader(value = "X-Anonymous-Id", required = false) String anonymousId,
            @PathVariable Long detectionId) {
        DeepfakeDetection detection = deepfakeDetectionService.getDetection(userId, anonymousId, detectionId);
        return ResponseEntity.ok(DeepfakeDetectionResponse.from(detection, webSearchService.find(detectionId)));
    }
}
