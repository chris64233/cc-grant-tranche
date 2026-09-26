package com.chris64233.granttranche.domain;

/**
 * 拨款状态：已批准（未支付） -> 已支付；未支付可撤销。
 */
public enum DisbursementStatus {
    APPROVED,
    PAID,
    REVOKED
}
