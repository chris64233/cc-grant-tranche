package com.chris64233.granttranche.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 期次成果提交请求。 */
public record SubmitDeliverableRequest(
        @NotBlank @Size(max = 2000) String evidence,
        @NotBlank @Size(max = 64) String submittedBy) {
}
