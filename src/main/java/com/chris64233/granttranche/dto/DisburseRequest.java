package com.chris64233.granttranche.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 批准拨款请求。业务号由调用方提供，重复提交返回同一笔拨款，保证幂等。 */
public record DisburseRequest(
        @NotBlank @Size(max = 64) String businessNo,
        @NotBlank @Size(max = 64) String approvedBy,
        @Size(max = 1000) String reason) {
}
