package com.enzoguimaraes.fluxo.client;

import java.time.Instant;
import java.util.UUID;

public record ClientResponse(
        UUID id,
        String name,
        String email,
        Instant createdAt
) {
    static ClientResponse from(ClientEntity client) {
        return new ClientResponse(
                client.getId(),
                client.getName(),
                client.getEmail(),
                client.getCreatedAt()
        );
    }
}
