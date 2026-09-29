package com.enzoguimaraes.fluxo.payment;

public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException() {
        super("Idempotency key is already associated with another charge");
    }
}
