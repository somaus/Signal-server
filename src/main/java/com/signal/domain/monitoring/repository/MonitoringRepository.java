package com.signal.domain.monitoring.repository;

import com.signal.domain.monitoring.entity.Monitoring;
import com.signal.domain.monitoring.entity.MonitoringStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoringRepository extends JpaRepository<Monitoring, Long> {

    List<Monitoring> findByStatusAndNextSearchAtLessThanEqualAndCreatedAtBeforeOrderByNextSearchAtAsc(
            MonitoringStatus status, LocalDateTime now, LocalDateTime createdBefore, Pageable pageable);

    long countByLastSearchedAtGreaterThanEqual(LocalDateTime since);

    long countByUserId(Long userId);
}
