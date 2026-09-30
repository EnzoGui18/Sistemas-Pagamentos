package com.enzoguimaraes.fluxo.dashboard;

import java.time.LocalDate;

public record DashboardSummaryResponse(
        LocalDate referenceDate,
        DashboardMetric pending,
        DashboardMetric overdue,
        DashboardMetric paid,
        DashboardMetric canceled,
        DashboardMetric receivable
) {
}
