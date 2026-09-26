package com.chris64233.granttranche.repo;

import com.chris64233.granttranche.domain.ComplianceSuspension;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComplianceSuspensionRepository extends JpaRepository<ComplianceSuspension, Long> {

    List<ComplianceSuspension> findByProjectIdOrderByRaisedAtDesc(Long projectId);

    boolean existsByProjectIdAndActiveTrue(Long projectId);
}
