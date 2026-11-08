package com.signal.domain.agency.repository;

import com.signal.domain.agency.entity.AgencyConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AgencyConnectionRepository extends JpaRepository<AgencyConnection, Long> {

    List<AgencyConnection> findByUserId(Long userId);
}
