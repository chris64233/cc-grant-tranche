package com.chris64233.granttranche.repo;

import com.chris64233.granttranche.domain.Disbursement;
import com.chris64233.granttranche.domain.DisbursementStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DisbursementRepository extends JpaRepository<Disbursement, Long> {

    Optional<Disbursement> findByBusinessNo(String businessNo);

    boolean existsByTrancheIdAndStatusNot(Long trancheId, DisbursementStatus status);

    List<Disbursement> findByProjectIdOrderByCreatedAtAsc(Long projectId);
}
