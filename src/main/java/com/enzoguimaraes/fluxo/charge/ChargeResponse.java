package com.enzoguimaraes.fluxo.charge;

import com.enzoguimaraes.fluxo.client.ClientResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ChargeResponse(
        UUID id,
        ChargeClientResponse client,
        String description,
        BigDecimal amount,
        String currency,
        ChargeStatus status,
        ChargeCondition condition,
        LocalDate dueDate,
        Instant createdAt,
        Instant updatedAt
) {
    static ChargeResponse from(ChargeEntity charge, ClientResponse client, LocalDate today) {
        return new ChargeResponse(
                charge.getId(),
                ChargeClientResponse.from(client),
                charge.getDescription(),
                charge.getAmount(),
                charge.getCurrency(),
                charge.getStatus(),
                conditionOf(charge, today),
                charge.getDueDate(),
                charge.getCreatedAt(),
                charge.getUpdatedAt()
        );
    }

    private static ChargeCondition conditionOf(ChargeEntity charge, LocalDate today) {
        if (charge.getStatus() == ChargeStatus.PENDING && charge.getDueDate().isBefore(today)) {
            return ChargeCondition.OVERDUE;
        }
        return ChargeCondition.valueOf(charge.getStatus().name());
    }
}
