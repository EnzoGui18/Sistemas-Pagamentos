package com.enzoguimaraes.fluxo.client;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Validated
@RestController
@RequestMapping("/api/v1/clients")
public class ClientController {

    private final ClientService service;

    ClientController(ClientService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ClientResponse> create(@Valid @RequestBody CreateClientRequest request) {
        var client = service.create(request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(client.id())
                .toUri();
        return ResponseEntity.created(location).body(client);
    }

    @GetMapping
    ClientPageResponse search(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "Page must be zero or greater") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "Size must be at least 1")
            @Max(value = 100, message = "Size must be at most 100") int size
    ) {
        return service.search(search, page, size);
    }
}
