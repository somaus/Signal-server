package com.signal.domain.report.entity;

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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportStatus status;

    private LocalDate incidentDate;

    private String discoveryRoute;

    private String damageType;

    @Column(nullable = false, length = 3000)
    private String description;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "report_source_urls", joinColumns = @JoinColumn(name = "report_id"))
    @OrderColumn(name = "source_url_order")
    @Column(name = "source_url", nullable = false, length = 1000)
    private List<String> sourceUrls;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "report_evidence_ids", joinColumns = @JoinColumn(name = "report_id"))
    @OrderColumn(name = "evidence_id_order")
    @Column(name = "evidence_id", nullable = false)
    private List<Long> evidenceIds;

    private String targetAgencyType;

    @Column(length = 3000)
    private String additionalNotes;

    /** AI가 요약한 사건 개요. 요약을 만들지 못했거나 원문이 짧으면 null이다. */
    @Column(length = 1500)
    private String summary;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "report_timeline", joinColumns = @JoinColumn(name = "report_id"))
    @OrderColumn(name = "timeline_order")
    private List<TimelineEntry> timeline;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public Report(Long userId, LocalDate incidentDate, String discoveryRoute, String damageType,
                  String description, List<String> sourceUrls, List<Long> evidenceIds,
                  String targetAgencyType, String additionalNotes) {
        this.userId = userId;
        this.status = ReportStatus.DRAFT;
        this.incidentDate = incidentDate;
        this.discoveryRoute = discoveryRoute;
        this.damageType = damageType;
        this.description = description;
        this.sourceUrls = sourceUrls;
        this.evidenceIds = evidenceIds;
        this.targetAgencyType = targetAgencyType;
        this.additionalNotes = additionalNotes;
        this.timeline = new ArrayList<>();
        this.timeline.add(TimelineEntry.builder().event(TimelineEvent.CREATED).occurredAt(LocalDateTime.now()).build());
        this.createdAt = LocalDateTime.now();
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    /**
     * null인 Optional은 "변경 없음", 비어 있는 Optional은 "값 지우기"를 뜻한다.
     */
    public void applyPatch(Optional<LocalDate> incidentDate, Optional<String> discoveryRoute,
                            Optional<String> damageType, String description,
                            Optional<List<String>> sourceUrls, Optional<List<Long>> evidenceIds,
                            Optional<String> targetAgencyType, Optional<String> additionalNotes) {
        if (incidentDate != null) {
            this.incidentDate = incidentDate.orElse(null);
        }
        if (discoveryRoute != null) {
            this.discoveryRoute = discoveryRoute.orElse(null);
        }
        if (damageType != null) {
            this.damageType = damageType.orElse(null);
        }
        if (description != null) {
            this.description = description;
        }
        if (sourceUrls != null) {
            this.sourceUrls = new ArrayList<>(sourceUrls.orElse(List.of()));
        }
        if (evidenceIds != null) {
            this.evidenceIds = new ArrayList<>(evidenceIds.orElse(List.of()));
        }
        if (targetAgencyType != null) {
            this.targetAgencyType = targetAgencyType.orElse(null);
        }
        if (additionalNotes != null) {
            this.additionalNotes = additionalNotes.orElse(null);
        }
        this.timeline.add(TimelineEntry.builder().event(TimelineEvent.UPDATED).occurredAt(LocalDateTime.now()).build());
    }

    public void updateSummary(String summary) {
        this.summary = summary;
    }

    public void markFinalized() {
        this.status = ReportStatus.FINALIZED;
        this.timeline.add(TimelineEntry.builder().event(TimelineEvent.FINALIZED).occurredAt(LocalDateTime.now()).build());
    }
}
