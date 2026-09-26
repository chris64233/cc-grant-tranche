package com.chris64233.granttranche.repo;

import com.chris64233.granttranche.domain.ComplianceHold;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComplianceHoldRepository extends JpaRepository<ComplianceHold, Long> {

    boolean existsByProjectIdAndActiveTrue(Long projectId);

    List<ComplianceHold> findByProjectIdOrderByCreatedAtAsc(Long projectId);
}
