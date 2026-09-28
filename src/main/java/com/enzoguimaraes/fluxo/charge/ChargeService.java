package com.enzoguimaraes.fluxo.charge;

import com.enzoguimaraes.fluxo.client.ClientNotFoundException;
import com.enzoguimaraes.fluxo.client.ClientResponse;
import com.enzoguimaraes.fluxo.client.ClientService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ChargeService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Sort CHARGE_ORDER = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.asc("id")
    );

    private final ChargeRepository repository;
    private final ChargeEventRepository eventRepository;
    private final ClientService clientService;
    private final Clock clock;

    ChargeService(
            ChargeRepository repository,
            ChargeEventRepository eventRepository,
            ClientService clientService,
            Clock clock
    ) {
        this.repository = repository;
        this.eventRepository = eventRepository;
        this.clientService = clientService;
        this.clock = clock;
    }

    @Transactional
    public ChargeResponse create(CreateChargeRequest request) {
        var client = clientService.getRequired(request.clientId());
        var now = clock.instant();
        var today = today(now);
        if (request.dueDate().isBefore(today)) {
            throw new InvalidChargeDueDateException();
        }

        var charge = repository.save(new ChargeEntity(
                UUID.randomUUID(),
                client.id(),
                request.description(),
                request.amount(),
                request.dueDate(),
                now
        ));
        eventRepository.save(new ChargeEventEntity(
                UUID.randomUUID(),
                charge.getId(),
                ChargeEventType.CREATED,
                now
        ));
        return ChargeResponse.from(charge, client, today);
    }

    @Transactional(readOnly = true)
    public ChargePageResponse search(
            ChargeCondition condition,
            UUID clientId,
            int page,
            int size
    ) {
        var today = today();
        var charges = repository.findAll(specification(condition, clientId, today),
                PageRequest.of(page, size, CHARGE_ORDER));
        var clients = clientsFor(charges.getContent().stream()
                .map(ChargeEntity::getClientId)
                .collect(Collectors.toSet()));
        var content = charges.getContent().stream()
                .map(charge -> ChargeResponse.from(charge, clients.get(charge.getClientId()), today))
                .toList();
        return new ChargePageResponse(
                content,
                charges.getNumber(),
                charges.getSize(),
                charges.getTotalElements(),
                charges.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public ChargeResponse get(UUID id) {
        var charge = repository.findById(id).orElseThrow(ChargeNotFoundException::new);
        var client = clientService.getRequired(charge.getClientId());
        return ChargeResponse.from(charge, client, today());
    }

    private Specification<ChargeEntity> specification(
            ChargeCondition condition,
            UUID clientId,
            LocalDate today
    ) {
        return (root, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            if (clientId != null) {
                predicates.add(builder.equal(root.get("clientId"), clientId));
            }
            if (condition != null) {
                switch (condition) {
                    case PENDING -> {
                        predicates.add(builder.equal(root.get("status"), ChargeStatus.PENDING));
                        predicates.add(builder.greaterThanOrEqualTo(root.get("dueDate"), today));
                    }
                    case OVERDUE -> {
                        predicates.add(builder.equal(root.get("status"), ChargeStatus.PENDING));
                        predicates.add(builder.lessThan(root.get("dueDate"), today));
                    }
                    case PAID -> predicates.add(builder.equal(root.get("status"), ChargeStatus.PAID));
                    case CANCELED -> predicates.add(builder.equal(root.get("status"), ChargeStatus.CANCELED));
                }
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Map<UUID, ClientResponse> clientsFor(Set<UUID> clientIds) {
        var clients = clientService.findByIds(clientIds);
        if (clients.size() != clientIds.size()) {
            throw new ClientNotFoundException();
        }
        return clients;
    }

    private LocalDate today() {
        return today(clock.instant());
    }

    private LocalDate today(Instant instant) {
        return LocalDate.ofInstant(instant, BUSINESS_ZONE);
    }
}
