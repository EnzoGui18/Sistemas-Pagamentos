package com.enzoguimaraes.fluxo.charge;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateChargeRequest(
        @NotNull(message = "Client is required") UUID clientId,

        @NotBlank(message = "Description is required")
        @Size(max = 200, message = "Description must have at most 200 characters")
        String description,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.00", inclusive = false, message = "Amount must be greater than zero")
        @DecimalMax(value = "1000000.00", message = "Amount must be at most 1000000.00")
        @Digits(integer = 7, fraction = 2, message = "Amount must have at most two decimal places")
        BigDecimal amount,

        @NotNull(message = "Due date is required") LocalDate dueDate
) {
    public CreateChargeRequest {
        description = description == null ? null : description.trim();
    }
}
