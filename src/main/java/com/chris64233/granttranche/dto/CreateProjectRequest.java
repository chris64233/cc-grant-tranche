package com.chris64233.granttranche.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** 创建资助项目及其有序拨款期次的请求。 */
public record CreateProjectRequest(
        @NotBlank @Size(max = 64) String projectCode,
        @NotBlank @Size(max = 200) String title,
        @NotNull @Positive BigDecimal approvedAmount,
        @NotEmpty @Valid List<TrancheSpec> tranches) {

    public record TrancheSpec(
            @NotNull Integer sequenceNo,
            @NotNull @Positive BigDecimal plannedAmount,
            @NotBlank @Size(max = 1000) String requiredDeliverable,
            @NotBlank @Size(max = 1000) String budgetConditions) {
    }
}
