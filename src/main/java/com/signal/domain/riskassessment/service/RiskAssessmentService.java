package com.signal.domain.riskassessment.service;

import com.signal.domain.riskassessment.entity.AssessmentFailureReason;
import com.signal.domain.riskassessment.entity.RiskAssessment;
import com.signal.domain.riskassessment.entity.RiskAssessmentStatus;
import com.signal.domain.riskassessment.repository.RiskAssessmentRepository;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.file.FileStorage;
import com.signal.global.file.UploadFileValidator;
import com.signal.global.util.TransactionUtils;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RiskAssessmentService {

    private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");
    private static final String IMAGE_DIRECTORY = "risk-assessments";
    private static final long ANONYMOUS_ASSESSMENT_LIMIT = 5;
    /** 서버 재시작 등으로 분석 작업이 사라져 PROCESSING에 머문 건을 조회 시점에 실패로 정리하는 기준. */
    private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(5);

    private final RiskAssessmentRepository riskAssessmentRepository;
    private final RiskAssessmentProcessor riskAssessmentProcessor;
    private final FileStorage fileStorage;

    /**
     * 사진을 접수하고 PROCESSING 상태로 즉시 돌려준다. 분석은 커밋 이후 백그라운드에서 수행되며, 결과는
     * {@link #getAssessment}로 조회한다. 얼굴 미검출·AI 오류는 예외가 아니라 FAILED와 사유로 표현한다.
     */
    @Transactional
    public RiskAssessment createAssessment(Long userId, String anonymousId, MultipartFile image) {
        UploadFileValidator.validate(image, MAX_IMAGE_SIZE, ALLOWED_CONTENT_TYPES);
        if (userId == null) {
            validateAnonymousUsage(anonymousId);
        }

        byte[] content = readBytes(image);
        String imageUrl = fileStorage.store(image, IMAGE_DIRECTORY);

        RiskAssessment saved = riskAssessmentRepository.save(
                RiskAssessment.processing(userId, userId == null ? anonymousId : null, imageUrl));

        String filename = image.getOriginalFilename();
        String contentType = image.getContentType();
        TransactionUtils.runAfterCommit(
                () -> riskAssessmentProcessor.process(saved.getId(), filename, contentType, content));

        return saved;
    }

    @Transactional
    public RiskAssessment getAssessment(Long userId, String anonymousId, Long assessmentId) {
        RiskAssessment riskAssessment = riskAssessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new SignalException(ErrorCode.NOT_FOUND));

        if (!riskAssessment.isOwnedBy(userId, anonymousId)) {
            throw new SignalException(ErrorCode.FORBIDDEN);
        }

        if (riskAssessment.getStatus() == RiskAssessmentStatus.PROCESSING
                && riskAssessment.getCreatedAt().isBefore(LocalDateTime.now().minus(PROCESSING_TIMEOUT))) {
            riskAssessment.fail(AssessmentFailureReason.TIMEOUT);
            return riskAssessmentRepository.save(riskAssessment);
        }

        return riskAssessment;
    }

    public List<RiskAssessment> getMyAssessments(Long userId) {
        // userId가 null이면 파생 쿼리가 user_id IS NULL로 바뀌어 모든 익명 분석 결과가 조회된다.
        if (userId == null) {
            throw new SignalException(ErrorCode.UNAUTHORIZED);
        }
        return riskAssessmentRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, RiskAssessmentStatus.COMPLETED);
    }

    private void validateAnonymousUsage(String anonymousId) {
        if (!StringUtils.hasText(anonymousId)) {
            throw new SignalException(ErrorCode.ANONYMOUS_ID_REQUIRED);
        }
        // 진행 중인 건도 횟수에 넣어야 동시에 여러 장을 올려 제한을 피하지 못한다.
        // 다만 서버가 재시작돼 5분이 지나도록 끝나지 않은 건은 조회 전까지 FAILED가 되지 못하므로 뺀다.
        long used = riskAssessmentRepository.countByAnonymousIdAndStatusNot(anonymousId, RiskAssessmentStatus.FAILED)
                - riskAssessmentRepository.countByAnonymousIdAndStatusAndCreatedAtBefore(
                        anonymousId, RiskAssessmentStatus.PROCESSING, LocalDateTime.now().minus(PROCESSING_TIMEOUT));
        if (used >= ANONYMOUS_ASSESSMENT_LIMIT) {
            throw new SignalException(ErrorCode.ANONYMOUS_RISK_ASSESSMENT_LIMIT_EXCEEDED);
        }
    }

    private byte[] readBytes(MultipartFile image) {
        try {
            return image.getBytes();
        } catch (IOException e) {
            throw new SignalException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
