package com.enzoguimaraes.fluxo.client;

import org.springframework.data.domain.Page;

import java.util.List;

public record ClientPageResponse(
        List<ClientResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    static ClientPageResponse from(Page<ClientEntity> clients) {
        return new ClientPageResponse(
                clients.getContent().stream().map(ClientResponse::from).toList(),
                clients.getNumber(),
                clients.getSize(),
                clients.getTotalElements(),
                clients.getTotalPages()
        );
    }
}
