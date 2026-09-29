package com.enzoguimaraes.fluxo.charge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "charges")
class ChargeEntity {

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChargeStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ChargeEntity() {
    }

    ChargeEntity(
            UUID id,
            UUID clientId,
            String description,
            BigDecimal amount,
            LocalDate dueDate,
            Instant createdAt
    ) {
        this.id = id;
        this.clientId = clientId;
        this.description = description;
        this.amount = amount;
        this.currency = "BRL";
        this.dueDate = dueDate;
        this.status = ChargeStatus.PENDING;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    UUID getId() {
        return id;
    }

    UUID getClientId() {
        return clientId;
    }

    String getDescription() {
        return description;
    }

    BigDecimal getAmount() {
        return amount;
    }

    String getCurrency() {
        return currency;
    }

    LocalDate getDueDate() {
        return dueDate;
    }

    ChargeStatus getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void markPaid(Instant occurredAt) {
        status = ChargeStatus.PAID;
        updatedAt = occurredAt;
    }

    void cancel(Instant occurredAt) {
        status = ChargeStatus.CANCELED;
        updatedAt = occurredAt;
    }
}
