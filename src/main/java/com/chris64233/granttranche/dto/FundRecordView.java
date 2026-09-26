package com.chris64233.granttranche.dto;

import com.chris64233.granttranche.domain.FundRecordStatus;
import com.chris64233.granttranche.domain.FundRecordType;

import java.math.BigDecimal;
import java.time.Instant;

/** 资金台账记录视图。 */
public record FundRecordView(Long id,
                             String businessNo,
                             FundRecordType recordType,
                             Long trancheId,
                             Long originalDisbursementId,
                             BigDecimal amount,
                             FundRecordStatus status,
                             BigDecimal recoveredAmount,
                             String createdBy,
                             Instant createdAt,
                             String reason,
                             Instant paidAt,
                             String paidBy,
                             Instant revokedAt,
                             String revokedBy,
                             String revokeReason) {
}
