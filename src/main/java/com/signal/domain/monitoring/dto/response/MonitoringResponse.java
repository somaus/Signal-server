package com.signal.domain.monitoring.dto.response;

import com.signal.domain.monitoring.entity.Monitoring;
import com.signal.domain.monitoring.entity.MonitoringStatus;
import java.time.LocalDateTime;

public record MonitoringResponse(
        Long monitoringId,
        MonitoringStatus status,
        LocalDateTime createdAt,
        LocalDateTime lastSearchedAt,
        LocalDateTime nextSearchAt
) {

    public static MonitoringResponse from(Monitoring monitoring) {
        return new MonitoringResponse(
                monitoring.getId(),
                monitoring.getStatus(),
                monitoring.getCreatedAt(),
                monitoring.getLastSearchedAt(),
                // 멈춘 모니터링은 다음 검색이 없다.
                monitoring.getStatus() == MonitoringStatus.ACTIVE ? monitoring.getNextSearchAt() : null);
    }
}
