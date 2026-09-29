package com.enzoguimaraes.fluxo.payment;

import com.enzoguimaraes.fluxo.charge.ChargeService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository repository;
    private final ChargeService chargeService;
    private final JdbcClient jdbcClient;
    private final Clock clock;

    PaymentService(
            PaymentRepository repository,
            ChargeService chargeService,
            JdbcClient jdbcClient,
            Clock clock
    ) {
        this.repository = repository;
        this.chargeService = chargeService;
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    @Transactional
    PaymentOutcome pay(UUID chargeId, String suppliedIdempotencyKey) {
        var idempotencyKey = suppliedIdempotencyKey.trim();
        lockIdempotencyKey(idempotencyKey);

        var existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            var payment = existing.orElseThrow();
            if (!payment.getChargeId().equals(chargeId)) {
                throw new IdempotencyKeyConflictException();
            }
            return new PaymentOutcome(PaymentResponse.from(payment), true);
        }

        var paidAt = clock.instant();
        var charge = chargeService.completePayment(chargeId, paidAt);
        var payment = repository.save(new PaymentEntity(
                UUID.randomUUID(),
                charge.chargeId(),
                charge.amount(),
                idempotencyKey,
                paidAt
        ));
        return new PaymentOutcome(PaymentResponse.from(payment), false);
    }

    private void lockIdempotencyKey(String idempotencyKey) {
        jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", idempotencyKey)
                .query()
                .singleValue();
    }
}
