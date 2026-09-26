package com.chris64233.granttranche.domain;

/** 拨款期次生命周期状态。 */
public enum TrancheStatus {
    /** 已计划，尚未提交成果。 */
    PLANNED,
    /** 成果已提交，等待验收。 */
    SUBMITTED,
    /** 成果验收通过，可批准拨款。 */
    ACCEPTED,
    /** 成果验收未通过，可重新提交成果。 */
    REJECTED,
    /** 拨款已批准（未支付）。 */
    DISBURSED,
    /** 拨款已支付。 */
    PAID
}
