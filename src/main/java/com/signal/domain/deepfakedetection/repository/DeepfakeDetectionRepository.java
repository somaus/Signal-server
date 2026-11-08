package com.signal.domain.deepfakedetection.repository;

import com.signal.domain.deepfakedetection.entity.DeepfakeDetection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DeepfakeDetectionRepository extends JpaRepository<DeepfakeDetection, Long> {

    List<DeepfakeDetection> findByUserId(Long userId);

    long countByAnonymousId(String anonymousId);
}
