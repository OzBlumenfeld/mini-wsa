package com.akamai.miniwsa.api.dto;

import com.akamai.miniwsa.domain.RuleCategory;
import com.akamai.miniwsa.domain.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RuleRequest(
        @NotBlank String id,
        @NotBlank String name,
        @NotBlank String message,
        @NotNull Severity severity,
        @NotNull RuleCategory category
) {
}
