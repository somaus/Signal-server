package com.signal.domain.report.repository;

import com.signal.domain.report.entity.ReportEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReportEvidenceRepository extends JpaRepository<ReportEvidence, Long> {

    List<ReportEvidence> findByUserId(Long userId);
}
