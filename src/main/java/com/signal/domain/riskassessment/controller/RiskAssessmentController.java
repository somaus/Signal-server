package com.signal.domain.riskassessment.controller;

import com.signal.domain.riskassessment.dto.response.RiskAssessmentResponse;
import com.signal.domain.riskassessment.entity.RiskAssessment;
import com.signal.domain.riskassessment.service.RiskAssessmentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/risk-assessments")
@RequiredArgsConstructor
public class RiskAssessmentController {

    private final RiskAssessmentService riskAssessmentService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RiskAssessmentResponse> createAssessment(
            @AuthenticationPrincipal Long userId,
            @RequestHeader(value = "X-Anonymous-Id", required = false) String anonymousId,
            @RequestPart("image") MultipartFile image,
            @RequestParam(value = "webSearchConsent", defaultValue = "false") boolean webSearchConsent) {
        RiskAssessment riskAssessment =
                riskAssessmentService.createAssessment(userId, anonymousId, image, webSearchConsent);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(RiskAssessmentResponse.from(riskAssessment));
    }

    @GetMapping
    public ResponseEntity<List<RiskAssessmentResponse>> getMyAssessments(@AuthenticationPrincipal Long userId) {
        List<RiskAssessmentResponse> assessments = riskAssessmentService.getMyAssessments(userId).stream()
                .map(RiskAssessmentResponse::from)
                .toList();
        return ResponseEntity.ok(assessments);
    }

    @GetMapping("/{assessmentId}")
    public ResponseEntity<RiskAssessmentResponse> getAssessment(
            @AuthenticationPrincipal Long userId,
            @RequestHeader(value = "X-Anonymous-Id", required = false) String anonymousId,
            @PathVariable Long assessmentId) {
        RiskAssessment riskAssessment = riskAssessmentService.getAssessment(userId, anonymousId, assessmentId);
        return ResponseEntity.ok(RiskAssessmentResponse.from(riskAssessment));
    }
}
