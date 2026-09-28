package com.enzoguimaraes.fluxo.charge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ChargeEventRepository extends JpaRepository<ChargeEventEntity, UUID> {
}
