package com.chris64233.granttranche.dto;

import java.time.Instant;

/** 合规暂停记录视图。 */
public record SuspensionView(Long id,
                             boolean active,
                             String reason,
                             String raisedBy,
                             Instant raisedAt,
                             String liftedReason,
                             String liftedBy,
                             Instant liftedAt) {
}
