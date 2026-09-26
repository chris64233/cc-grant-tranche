package com.chris64233.granttranche.dto;

import com.chris64233.granttranche.domain.TrancheStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** 期次及其成果证据视图。 */
public record TrancheView(Long id,
                          Integer sequenceNo,
                          BigDecimal plannedAmount,
                          String requiredDeliverable,
                          String budgetConditions,
                          TrancheStatus status,
                          String deliverableEvidence,
                          Instant submittedAt,
                          String submittedBy,
                          String reviewComment,
                          Instant reviewedAt,
                          String reviewedBy) {
}
