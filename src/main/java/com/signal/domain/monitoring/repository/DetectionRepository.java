package com.signal.domain.monitoring.repository;

import com.signal.domain.monitoring.entity.Detection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DetectionRepository extends JpaRepository<Detection, Long> {

    Page<Detection> findByMonitoringIdOrderByDetectedAtDesc(Long monitoringId, Pageable pageable);

    boolean existsByMonitoringIdAndSourceUrl(Long monitoringId, String sourceUrl);

    void deleteAllByMonitoringId(Long monitoringId);
}
