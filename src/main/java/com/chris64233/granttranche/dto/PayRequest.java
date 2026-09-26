package com.chris64233.granttranche.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 支付确认请求。 */
public record PayRequest(
        @NotBlank @Size(max = 64) String paidBy) {
}
