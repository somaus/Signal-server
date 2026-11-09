package com.signal.domain.deepfakedetection.entity;

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
@Table(name = "deepfake_detections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeepfakeDetection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private String anonymousId;

    @Column(nullable = false)
    private String fileUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeepfakeDetectionStatus status;

    @Enumerated(EnumType.STRING)
    private Verdict verdict;

    private Double confidence;

    private Integer riskScore;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "deepfake_detection_evidences", joinColumns = @JoinColumn(name = "deepfake_detection_id"))
    @OrderColumn(name = "evidence_order")
    private List<Evidence> evidences;

    private String highlightedResultUrl;

    /**
     * true면 이번 결과가 실제 비전 LLM 응답이 아니라 {@link com.signal.domain.deepfakedetection.detector.StubDeepfakeDetector}의
     * 파일 해시 기반 결정론적 폴백값이라는 뜻이다 (LLM 미설정/호출·파싱 실패, 영상 입력 등). 완료 전에는 null.
     */
    private Boolean fallbackUsed;

    /**
     * 합성 가능성 등급({@link DetectionBand} 이름). 전용 탐지 모델(mode=http)로 분석한 결과에만 값이 있고,
     * 비전 LLM(mode=llm)이나 이전 기록은 null이다. enum 컬럼 타입 대신 문자열로 저장한다.
     */
    @Column(length = 20)
    private String band;

    /** 등급을 정한 근거({@link AssessmentBasis} 이름). band가 있을 때만 값이 있다. */
    @Column(length = 20)
    private String assessmentBasis;

    /** 등급이 UNDETERMINED(판단 불가)일 때의 이유({@link UndeterminedReason} 이름). */
    @Column(length = 30)
    private String undeterminedReason;

    /** 점수를 낸 탐지 모델의 이름과 버전(가중치 해시 포함). 어떤 모델로 낸 결과인지 추적하려고 남긴다. */
    @Column(length = 100)
    private String modelName;

    @Column(length = 100)
    private String modelVersion;

    /** 파일에서 찾은 출처 신호({@link ProvenanceSignal} 이름). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "deepfake_detection_signals", joinColumns = @JoinColumn(name = "deepfake_detection_id"))
    @OrderColumn(name = "signal_order")
    @Column(name = "signal_code", length = 40)
    private List<String> provenanceSignals;

    /**
     * status가 FAILED일 때의 실패 사유. 그 외 상태에서는 null.
     */
    @Enumerated(EnumType.STRING)
    private DetectionFailureReason failureReason;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public DeepfakeDetection(Long userId, String anonymousId, String fileUrl) {
        this.userId = userId;
        this.anonymousId = anonymousId;
        this.fileUrl = fileUrl;
        this.status = DeepfakeDetectionStatus.PROCESSING;
        this.evidences = new ArrayList<>();
        this.provenanceSignals = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
    }

    public boolean isOwnedBy(Long userId, String anonymousId) {
        if (this.userId != null) {
            return this.userId.equals(userId);
        }
        return Objects.equals(this.anonymousId, anonymousId);
    }

    public void complete(Verdict verdict, double confidence, int riskScore,
                          List<Evidence> evidences, String highlightedResultUrl, boolean fallbackUsed) {
        this.status = DeepfakeDetectionStatus.COMPLETED;
        this.verdict = verdict;
        this.confidence = confidence;
        this.riskScore = riskScore;
        this.evidences = evidences;
        this.highlightedResultUrl = highlightedResultUrl;
        this.fallbackUsed = fallbackUsed;
    }

    /**
     * 전용 탐지 모델의 결과를 등급과 함께 기록한다. 등급이 점수에서 나오지 않은 경우(판단 불가, 출처 신호로 높임)에는
     * 점수와 모순되는 숫자를 보여 주지 않도록 verdict, confidence, riskScore를 null로 둘 수 있다.
     */
    public void completeAssessed(DetectionBand band, AssessmentBasis basis, UndeterminedReason undeterminedReason,
                                  Verdict verdict, Double confidence, Integer riskScore,
                                  List<Evidence> evidences, String highlightedResultUrl,
                                  String modelName, String modelVersion, List<ProvenanceSignal> signals) {
        this.status = DeepfakeDetectionStatus.COMPLETED;
        this.band = band.name();
        this.assessmentBasis = basis == null ? null : basis.name();
        this.undeterminedReason = undeterminedReason == null ? null : undeterminedReason.name();
        this.verdict = verdict;
        this.confidence = confidence;
        this.riskScore = riskScore;
        this.evidences = evidences;
        this.highlightedResultUrl = highlightedResultUrl;
        this.fallbackUsed = false;
        this.modelName = modelName;
        this.modelVersion = modelVersion;
        this.provenanceSignals = new ArrayList<>(signals.stream().map(ProvenanceSignal::name).toList());
    }

    public void fail(DetectionFailureReason failureReason) {
        this.status = DeepfakeDetectionStatus.FAILED;
        this.failureReason = failureReason;
    }
}
