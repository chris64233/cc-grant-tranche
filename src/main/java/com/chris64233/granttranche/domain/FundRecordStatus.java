package com.chris64233.granttranche.domain;

/** 拨款资金记录状态（追回记录始终为 EFFECTIVE）。 */
public enum FundRecordStatus {
    /** 已批准、尚未支付，可撤销。 */
    UNPAID,
    /** 已支付，不可撤销，只能通过追回减少净拨款。 */
    PAID,
    /** 已撤销，额度已恢复。 */
    REVOKED
}
