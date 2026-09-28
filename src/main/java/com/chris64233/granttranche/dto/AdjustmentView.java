package com.chris64233.granttranche.dto;

import com.chris64233.granttranche.domain.AdjustmentStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** 预算调整记录视图：完整保留原因、申请人、确认批准人，以及调出/调入期次调整前后的金额。 */
public record AdjustmentView(Long id,
                             String businessNo,
                             Long projectId,
                             Long fromTrancheId,
                             Integer fromSequenceNo,
                             Long toTrancheId,
                             Integer toSequenceNo,
                             BigDecimal amount,
                             AdjustmentStatus status,
                             String reason,
                             String requestedBy,
                             Instant requestedAt,
                             String confirmedBy,
                             Instant confirmedAt,
                             BigDecimal fromAmountBefore,
                             BigDecimal fromAmountAfter,
                             BigDecimal toAmountBefore,
                             BigDecimal toAmountAfter,
                             Long projectVersion,
                             String invalidatedReason,
                             Instant invalidatedAt) {
}
