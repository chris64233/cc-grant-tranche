package com.chris64233.granttranche.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 确认预算调整请求。确认时重新检查暂停、期次状态与余额；记录批准人。 */
public record ConfirmAdjustmentRequest(
        @NotBlank @Size(max = 64) String confirmedBy) {
}
