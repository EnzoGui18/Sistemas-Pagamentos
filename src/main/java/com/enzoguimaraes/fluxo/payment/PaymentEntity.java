package com.enzoguimaraes.fluxo.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
class PaymentEntity {

    @Id
    private UUID id;

    @Column(name = "charge_id", nullable = false, unique = true)
    private UUID chargeId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "paid_at", nullable = false)
    private Instant paidAt;

    protected PaymentEntity() {
    }

    PaymentEntity(UUID id, UUID chargeId, BigDecimal amount, String idempotencyKey, Instant paidAt) {
        this.id = id;
        this.chargeId = chargeId;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
        this.paidAt = paidAt;
    }

    UUID getId() {
        return id;
    }

    UUID getChargeId() {
        return chargeId;
    }

    BigDecimal getAmount() {
        return amount;
    }

    Instant getPaidAt() {
        return paidAt;
    }
}
