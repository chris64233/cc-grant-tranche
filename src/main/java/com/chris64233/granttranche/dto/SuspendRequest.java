package com.chris64233.granttranche.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 合规暂停请求。 */
public record SuspendRequest(
        @NotBlank @Size(max = 1000) String reason,
        @NotBlank @Size(max = 64) String raisedBy) {
}
