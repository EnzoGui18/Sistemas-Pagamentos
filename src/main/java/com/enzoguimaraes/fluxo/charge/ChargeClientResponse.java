package com.enzoguimaraes.fluxo.charge;

import com.enzoguimaraes.fluxo.client.ClientResponse;

import java.util.UUID;

public record ChargeClientResponse(UUID id, String name, String email) {

    static ChargeClientResponse from(ClientResponse client) {
        return new ChargeClientResponse(client.id(), client.name(), client.email());
    }
}
