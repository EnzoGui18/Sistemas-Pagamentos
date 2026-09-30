package com.enzoguimaraes.fluxo.dashboard;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class DashboardService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Sao_Paulo");

    private final DashboardRepository repository;
    private final Clock clock;

    DashboardService(DashboardRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse summary() {
        var referenceDate = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        return repository.summarize(referenceDate);
    }
}
