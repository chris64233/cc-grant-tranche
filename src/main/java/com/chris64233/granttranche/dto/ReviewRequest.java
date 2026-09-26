package com.chris64233.granttranche.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 验收决定请求。approved=true 验收通过；false 验收不通过并须给出原因。 */
public record ReviewRequest(
        @NotNull Boolean approved,
        @NotBlank @Size(max = 64) String reviewedBy,
        @Size(max = 1000) String comment) {
}
