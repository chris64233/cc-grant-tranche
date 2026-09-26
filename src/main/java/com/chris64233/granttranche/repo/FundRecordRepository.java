package com.chris64233.granttranche.repo;

import com.chris64233.granttranche.domain.FundRecord;
import com.chris64233.granttranche.domain.FundRecordType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface FundRecordRepository extends JpaRepository<FundRecord, Long> {

    Optional<FundRecord> findByBusinessNo(String businessNo);

    List<FundRecord> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    @Query("select coalesce(sum(r.amount), 0) from FundRecord r " +
            "where r.disbursement.id = :disbursementId and r.type = :type")
    BigDecimal sumAmountByDisbursementIdAndType(Long disbursementId, FundRecordType type);
}
