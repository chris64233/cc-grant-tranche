package com.chris64233.granttranche.repo;

import com.chris64233.granttranche.domain.FundRecord;
import com.chris64233.granttranche.domain.FundRecordType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FundRecordRepository extends JpaRepository<FundRecord, Long> {

    /** 按业务号查询，拨款/追回幂等的依据（数据库另有唯一约束兜底）。 */
    Optional<FundRecord> findByBusinessNo(String businessNo);

    List<FundRecord> findByProjectIdOrderByIdAsc(Long projectId);

    /** 某期次当前生效的拨款记录（UNPAID 或 PAID，不含 REVOKED）。 */
    Optional<FundRecord> findFirstByTrancheIdAndRecordTypeOrderByIdDesc(Long trancheId,
                                                                        FundRecordType recordType);

    List<FundRecord> findByOriginalDisbursementId(Long originalDisbursementId);
}
