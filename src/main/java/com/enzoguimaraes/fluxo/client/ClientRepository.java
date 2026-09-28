package com.enzoguimaraes.fluxo.client;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ClientRepository extends JpaRepository<ClientEntity, UUID> {

    boolean existsByEmail(String email);

    Page<ClientEntity> findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(
            String name,
            String email,
            Pageable pageable
    );
}
