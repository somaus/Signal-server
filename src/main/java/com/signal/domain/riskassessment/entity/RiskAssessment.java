package com.signal.domain.riskassessment.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "risk_assessments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private String anonymousId;

    @Column(nullable = false)
    private String imageUrl;

    /** 분석이 끝나기 전(PROCESSING)이나 실패(FAILED)했을 때는 null이다. */
    @Enumerated(EnumType.STRING)
    private RiskLevel overallRiskLevel;

    /** 분석이 끝나기 전(PROCESSING)이나 실패(FAILED)했을 때는 null이다. */
    private Integer overallScore;

    @Column(nullable = false)
    private boolean faceDetected;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "risk_assessment_factors", joinColumns = @JoinColumn(name = "risk_assessment_id"))
    @OrderColumn(name = "factor_order")
    private List<RiskFactor> factors;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "risk_assessment_recommendations", joinColumns = @JoinColumn(name = "risk_assessment_id"))
    @OrderColumn(name = "recommendation_order")
    @Column(name = "recommendation", length = 500)
    private List<String> recommendations;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "risk_assessment_faces", joinColumns = @JoinColumn(name = "risk_assessment_id"))
    @OrderColumn(name = "face_order")
    private List<DetectedFace> faces;

    /**
     * true면 이번 결과가 실제 비전 LLM 응답이 아니라 {@link com.signal.domain.riskassessment.analyzer.StubRiskAnalyzer}의
     * 파일 해시 기반 결정론적 폴백값이라는 뜻이다 (LLM 미설정/호출·파싱 실패 등).
     */
    @Column(nullable = false)
    private boolean fallbackUsed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RiskAssessmentStatus status;

    /** status가 FAILED일 때의 실패 사유. 그 외 상태에서는 null. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private AssessmentFailureReason failureReason;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** status를 주지 않으면 완료된 결과로 만든다(분석이 끝난 뒤 저장하던 기존 방식과 같다). */
    @Builder
    public RiskAssessment(Long userId, String anonymousId, String imageUrl, RiskLevel overallRiskLevel,
                           Integer overallScore, boolean faceDetected, List<RiskFactor> factors,
                           List<String> recommendations, List<DetectedFace> faces, boolean fallbackUsed,
                           RiskAssessmentStatus status) {
        this.userId = userId;
        this.anonymousId = anonymousId;
        this.imageUrl = imageUrl;
        this.overallRiskLevel = overallRiskLevel;
        this.overallScore = overallScore;
        this.faceDetected = faceDetected;
        this.factors = factors;
        this.recommendations = recommendations;
        this.faces = faces;
        this.fallbackUsed = fallbackUsed;
        this.status = status != null ? status : RiskAssessmentStatus.COMPLETED;
        this.createdAt = LocalDateTime.now();
    }

    /** 사진을 접수한 직후의 상태. 분석 결과는 비어 있고 {@link #complete} 또는 {@link #fail}로 마무리한다. */
    public static RiskAssessment processing(Long userId, String anonymousId, String imageUrl) {
        return RiskAssessment.builder()
                .userId(userId)
                .anonymousId(anonymousId)
                .imageUrl(imageUrl)
                .faceDetected(false)
                .factors(new ArrayList<>())
                .recommendations(new ArrayList<>())
                .faces(new ArrayList<>())
                .fallbackUsed(false)
                .status(RiskAssessmentStatus.PROCESSING)
                .build();
    }

    public void complete(RiskLevel overallRiskLevel, int overallScore, boolean faceDetected,
                         List<RiskFactor> factors, List<String> recommendations, List<DetectedFace> faces,
                         boolean fallbackUsed) {
        this.overallRiskLevel = overallRiskLevel;
        this.overallScore = overallScore;
        this.faceDetected = faceDetected;
        this.factors = replace(this.factors, factors);
        this.recommendations = replace(this.recommendations, recommendations);
        this.faces = replace(this.faces, faces);
        this.fallbackUsed = fallbackUsed;
        this.status = RiskAssessmentStatus.COMPLETED;
        this.failureReason = null;
    }

    public void fail(AssessmentFailureReason failureReason) {
        this.status = RiskAssessmentStatus.FAILED;
        this.failureReason = failureReason;
    }

    // 같은 리스트를 다시 넘기거나 builder로 만들어 target이 null이어도 안전하도록 먼저 복사한다.
    private static <T> List<T> replace(List<T> target, List<T> source) {
        List<T> copy = source == null ? List.of() : List.copyOf(source);
        if (target == null) {
            return new ArrayList<>(copy);
        }
        target.clear();
        target.addAll(copy);
        return target;
    }

    public boolean isOwnedBy(Long userId, String anonymousId) {
        if (this.userId != null) {
            return this.userId.equals(userId);
        }
        return Objects.equals(this.anonymousId, anonymousId);
    }
}
