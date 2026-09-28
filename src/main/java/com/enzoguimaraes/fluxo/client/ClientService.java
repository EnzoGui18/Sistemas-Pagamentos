package com.enzoguimaraes.fluxo.client;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ClientService {

    private static final Sort CLIENT_ORDER = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.asc("id")
    );

    private final ClientRepository repository;
    private final Clock clock;

    ClientService(ClientRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public ClientResponse create(CreateClientRequest request) {
        var email = normalizeEmail(request.email());
        if (repository.existsByEmail(email)) {
            throw new DuplicateClientEmailException();
        }

        var client = new ClientEntity(
                UUID.randomUUID(),
                request.name().trim(),
                email,
                clock.instant()
        );

        try {
            return ClientResponse.from(repository.saveAndFlush(client));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateClientEmailException();
        }
    }

    @Transactional(readOnly = true)
    public ClientPageResponse search(String search, int page, int size) {
        var pageable = PageRequest.of(page, size, CLIENT_ORDER);
        var normalizedSearch = search == null ? "" : search.trim();
        var clients = normalizedSearch.isEmpty()
                ? repository.findAll(pageable)
                : repository.findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(
                        normalizedSearch,
                        normalizedSearch,
                        pageable
                );
        return ClientPageResponse.from(clients);
    }

    @Transactional(readOnly = true)
    public ClientResponse getRequired(UUID id) {
        return repository.findById(id)
                .map(ClientResponse::from)
                .orElseThrow(ClientNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public Map<UUID, ClientResponse> findByIds(Set<UUID> ids) {
        return repository.findAllById(ids).stream()
                .map(ClientResponse::from)
                .collect(Collectors.toMap(ClientResponse::id, Function.identity()));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
