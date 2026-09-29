package com.enzoguimaraes.fluxo.charge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface ChargeEventRepository extends JpaRepository<ChargeEventEntity, UUID> {

    @Query(value = """
            SELECT *
            FROM charge_events
            WHERE charge_id = :chargeId
            ORDER BY occurred_at,
                     CASE type WHEN 'CREATED' THEN 0 ELSE 1 END,
                     id
            """, nativeQuery = true)
    List<ChargeEventEntity> findHistory(UUID chargeId);
}
