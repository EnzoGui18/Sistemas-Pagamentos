package com.enzoguimaraes.fluxo.charge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/charges")
public class ChargeController {

    private final ChargeService service;

    ChargeController(ChargeService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ChargeResponse> create(@Valid @RequestBody CreateChargeRequest request) {
        var charge = service.create(request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(charge.id())
                .toUri();
        return ResponseEntity.created(location).body(charge);
    }

    @GetMapping
    ChargePageResponse search(
            @RequestParam(required = false) ChargeCondition condition,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "Page must be zero or greater") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "Size must be at least 1")
            @Max(value = 100, message = "Size must be at most 100") int size
    ) {
        return service.search(condition, clientId, page, size);
    }

    @GetMapping("/{id}")
    ChargeResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/cancellation")
    ChargeResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    @GetMapping("/{id}/events")
    List<ChargeEventResponse> getEvents(@PathVariable UUID id) {
        return service.getEvents(id);
    }
}
