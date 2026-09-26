package com.chris64233.granttranche.domain;

/**
 * 资金台账记录类型：拨款（增加累计拨款）、撤销冲正、追回（减少净拨款，原记录不变）。
 */
public enum FundRecordType {
    DISBURSEMENT,
    REVERSAL,
    CLAWBACK
}
