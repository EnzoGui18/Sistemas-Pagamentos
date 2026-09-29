package com.enzoguimaraes.fluxo.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID chargeId,
        BigDecimal amount,
        String currency,
        Instant paidAt
) {
    static PaymentResponse from(PaymentEntity payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getChargeId(),
                payment.getAmount(),
                "BRL",
                payment.getPaidAt()
        );
    }
}
