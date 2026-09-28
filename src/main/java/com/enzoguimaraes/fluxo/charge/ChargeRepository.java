package com.enzoguimaraes.fluxo.charge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

interface ChargeRepository extends JpaRepository<ChargeEntity, UUID>, JpaSpecificationExecutor<ChargeEntity> {
}
