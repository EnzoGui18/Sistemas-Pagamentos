package com.enzoguimaraes.fluxo.charge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "charge_events")
class ChargeEventEntity {

    @Id
    private UUID id;

    @Column(name = "charge_id", nullable = false)
    private UUID chargeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChargeEventType type;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected ChargeEventEntity() {
    }

    ChargeEventEntity(UUID id, UUID chargeId, ChargeEventType type, Instant occurredAt) {
        this.id = id;
        this.chargeId = chargeId;
        this.type = type;
        this.occurredAt = occurredAt;
    }
}
