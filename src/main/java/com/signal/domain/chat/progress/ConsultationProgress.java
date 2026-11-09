package com.signal.domain.chat.progress;

import com.signal.domain.chat.engine.SituationType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 단계별 상태로부터 백엔드가 계산한 상담 안내 진행도. 피해 해결률이 아니라 안내 완료도다.
 *
 * @param currentStage    진행 중인 단계(없으면 아직 시작 전이거나 모두 완료라서 null)
 * @param completedStages 완료된 단계
 * @param stages          모든 단계의 상태(해당 없는 단계는 NOT_APPLICABLE)
 */
public record ConsultationProgress(
        ConsultationProgressStatus status,
        ConsultationStage currentStage,
        List<ConsultationStage> completedStages,
        Map<ConsultationStage, StageStatus> stages,
        int progressPercent
) {

    public static ConsultationProgress notStarted() {
        Map<ConsultationStage, StageStatus> stages = new EnumMap<>(ConsultationStage.class);
        for (ConsultationStage stage : ConsultationStage.values()) {
            stages.put(stage, StageStatus.NOT_STARTED);
        }
        return new ConsultationProgress(
                ConsultationProgressStatus.NOT_STARTED, null, List.of(), stages, 0);
    }

    public static ConsultationProgress from(StageSnapshot snapshot) {
        if (snapshot == null) {
            return notStarted();
        }
        SituationType type = snapshot.type();
        Map<ConsultationStage, StageStatus> stages = new EnumMap<>(ConsultationStage.class);
        for (ConsultationStage stage : ConsultationStage.values()) {
            stages.put(stage, stage.isApplicableTo(type)
                    ? snapshot.stages().getOrDefault(stage, StageStatus.NOT_STARTED)
                    : StageStatus.NOT_APPLICABLE);
        }

        List<ConsultationStage> applicable = ConsultationStage.applicableTo(type);
        List<ConsultationStage> completed = applicable.stream()
                .filter(stage -> stages.get(stage) == StageStatus.COMPLETED)
                .toList();
        ConsultationStage current = applicable.stream()
                .filter(stage -> stages.get(stage) == StageStatus.IN_PROGRESS)
                .findFirst()
                .orElseGet(() -> completed.size() == applicable.size() ? null : applicable.stream()
                        .filter(stage -> stages.get(stage) == StageStatus.NOT_STARTED)
                        .findFirst()
                        .orElse(null));

        int percent = (int) Math.round(completed.size() * 100.0 / applicable.size());
        ConsultationProgressStatus status = completed.size() == applicable.size()
                ? ConsultationProgressStatus.COMPLETED
                : ConsultationProgressStatus.IN_PROGRESS;
        return new ConsultationProgress(status, current, completed, stages, percent);
    }
}
