package com.chris64233.granttranche.domain;

/**
 * 期次状态：待提交 -> 已提交（验收中） -> 验收通过 / 验收驳回（可重新提交）。
 */
public enum TrancheStatus {
    PENDING,
    SUBMITTED,
    ACCEPTED,
    REJECTED
}
