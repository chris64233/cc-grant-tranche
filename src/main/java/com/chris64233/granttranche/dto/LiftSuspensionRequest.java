package com.chris64233.granttranche.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 解除合规暂停请求。 */
public record LiftSuspensionRequest(
        @NotBlank @Size(max = 64) String liftedBy,
        @NotBlank @Size(max = 1000) String reason) {
}
