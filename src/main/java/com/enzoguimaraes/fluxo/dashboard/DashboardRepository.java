package com.enzoguimaraes.fluxo.dashboard;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;

@Repository
class DashboardRepository {

    private final JdbcClient jdbcClient;

    DashboardRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    DashboardSummaryResponse summarize(LocalDate referenceDate) {
        return jdbcClient.sql("""
                        SELECT
                            COUNT(*) FILTER (
                                WHERE status = 'PENDING' AND due_date >= :referenceDate
                            ) AS pending_count,
                            COALESCE(SUM(amount) FILTER (
                                WHERE status = 'PENDING' AND due_date >= :referenceDate
                            ), 0) AS pending_amount,
                            COUNT(*) FILTER (
                                WHERE status = 'PENDING' AND due_date < :referenceDate
                            ) AS overdue_count,
                            COALESCE(SUM(amount) FILTER (
                                WHERE status = 'PENDING' AND due_date < :referenceDate
                            ), 0) AS overdue_amount,
                            COUNT(*) FILTER (WHERE status = 'PAID') AS paid_count,
                            COALESCE(SUM(amount) FILTER (WHERE status = 'PAID'), 0) AS paid_amount,
                            COUNT(*) FILTER (WHERE status = 'CANCELED') AS canceled_count,
                            COALESCE(SUM(amount) FILTER (WHERE status = 'CANCELED'), 0) AS canceled_amount,
                            COUNT(*) FILTER (WHERE status = 'PENDING') AS receivable_count,
                            COALESCE(SUM(amount) FILTER (WHERE status = 'PENDING'), 0) AS receivable_amount
                        FROM charges
                        """)
                .param("referenceDate", referenceDate)
                .query((resultSet, rowNumber) -> new DashboardSummaryResponse(
                        referenceDate,
                        metric(resultSet.getLong("pending_count"), resultSet.getBigDecimal("pending_amount")),
                        metric(resultSet.getLong("overdue_count"), resultSet.getBigDecimal("overdue_amount")),
                        metric(resultSet.getLong("paid_count"), resultSet.getBigDecimal("paid_amount")),
                        metric(resultSet.getLong("canceled_count"), resultSet.getBigDecimal("canceled_amount")),
                        metric(resultSet.getLong("receivable_count"), resultSet.getBigDecimal("receivable_amount"))
                ))
                .single();
    }

    private DashboardMetric metric(long count, BigDecimal amount) {
        return new DashboardMetric(count, amount);
    }
}
