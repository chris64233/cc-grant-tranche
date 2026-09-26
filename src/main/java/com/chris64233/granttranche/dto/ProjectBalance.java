package com.chris64233.granttranche.dto;

import java.math.BigDecimal;

/**
 * 项目余额视图。
 *
 * @param approvedAmount 批准总额
 * @param committedAmount 已承诺（累计拨款：未支付 + 已支付 - 已撤销）
 * @param recoveredAmount 已追回总额
 * @param netPaidAmount 净支付额 = 已支付拨款 - 已追回
 * @param availableBalance 可继续拨款余额 = 批准总额 - 已承诺
 */
public record ProjectBalance(BigDecimal approvedAmount,
                             BigDecimal committedAmount,
                             BigDecimal recoveredAmount,
                             BigDecimal netPaidAmount,
                             BigDecimal availableBalance) {
}
