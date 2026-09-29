package com.enzoguimaraes.fluxo.charge;

import java.time.Instant;
import java.util.UUID;

public record ChargeEventResponse(UUID id, ChargeEventType type, Instant occurredAt) {

    static ChargeEventResponse from(ChargeEventEntity event) {
        return new ChargeEventResponse(event.getId(), event.getType(), event.getOccurredAt());
    }
}
