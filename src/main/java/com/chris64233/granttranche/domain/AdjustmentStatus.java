package com.chris64233.granttranche.domain;

/** 未拨付预算在期次间调整的生命周期状态。 */
public enum AdjustmentStatus {
    /** 已申请，等待确认；确认前不改变任何期次金额。 */
    PROPOSED,
    /** 已确认：两个期次的计划金额在同一事务内等额转移完成。 */
    CONFIRMED,
    /**
     * 已失效：合规暂停发生、申请所依据的期次状态/余额或项目版本在确认前已变化，
     * 旧方案不能直接沿用，须重新申请。
     */
    INVALIDATED
}
