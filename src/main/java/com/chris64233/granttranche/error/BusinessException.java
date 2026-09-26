package com.chris64233.granttranche.error;

/**
 * 业务规则冲突（验收未通过、存在合规暂停、超额度、状态不允许等）。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
