package com.chris64233.granttranche.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 申请未拨付预算在两个未来期次之间调整的请求。
 *
 * <p>仅登记调整方案（PROPOSED），不改变任何金额；金额不得超过调出期次尚未拨付的计划金额。
 * 业务号由调用方提供，重复提交返回同一方案，保证幂等。
 */
public record AdjustmentRequest(
        @NotNull Long fromTrancheId,
        @NotNull Long toTrancheId,
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Size(max = 64) String businessNo,
        @NotBlank @Size(max = 1000) String reason,
        @NotBlank @Size(max = 64) String requestedBy) {
}
