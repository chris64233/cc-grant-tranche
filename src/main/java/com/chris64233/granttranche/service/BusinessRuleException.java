package com.chris64233.granttranche.service;

/** 业务规则冲突（状态不满足、超额、前置期次未拨款、合规暂停中等），映射 HTTP 409。 */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
