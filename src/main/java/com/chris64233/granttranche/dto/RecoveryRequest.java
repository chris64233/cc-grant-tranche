package com.chris64233.granttranche.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** 对已支付拨款发起追回的请求。金额不得超过该拨款尚未追回的净额。 */
public record RecoveryRequest(
        @NotBlank @Size(max = 64) String businessNo,
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Size(max = 64) String recoveredBy,
        @NotBlank @Size(max = 1000) String reason) {
}
