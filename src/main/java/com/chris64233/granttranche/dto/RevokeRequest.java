package com.chris64233.granttranche.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 撤销尚未支付的拨款请求，须给出经办人与原因。 */
public record RevokeRequest(
        @NotBlank @Size(max = 64) String revokedBy,
        @NotBlank @Size(max = 1000) String reason) {
}
