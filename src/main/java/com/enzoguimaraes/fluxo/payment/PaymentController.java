package com.enzoguimaraes.fluxo.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/charges/{chargeId}/payments")
public class PaymentController {

    private final PaymentService service;

    PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<PaymentResponse> pay(
            @PathVariable UUID chargeId,
            @RequestHeader(name = "Idempotency-Key", required = false)
            @NotBlank(message = "Idempotency-Key is required")
            @Size(max = 100, message = "Idempotency-Key must have at most 100 characters")
            String idempotencyKey
    ) {
        var outcome = service.pay(chargeId, idempotencyKey);
        if (outcome.replay()) {
            return ResponseEntity.ok(outcome.payment());
        }
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(outcome.payment().id())
                .toUri();
        return ResponseEntity.created(location).body(outcome.payment());
    }
}
