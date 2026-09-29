package com.enzoguimaraes.fluxo.charge;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface ChargeRepository extends JpaRepository<ChargeEntity, UUID>, JpaSpecificationExecutor<ChargeEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select charge from ChargeEntity charge where charge.id = :id")
    Optional<ChargeEntity> findByIdForUpdate(@Param("id") UUID id);
}
