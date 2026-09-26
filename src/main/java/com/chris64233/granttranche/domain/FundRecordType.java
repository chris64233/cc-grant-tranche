package com.chris64233.granttranche.domain;

/** 资金记录类型。 */
public enum FundRecordType {
    /** 拨款。 */
    DISBURSEMENT,
    /** 追回（针对已支付拨款的冲减记录）。 */
    RECOVERY
}
