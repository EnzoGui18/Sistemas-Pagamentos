package com.enzoguimaraes.fluxo;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlStartupIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void appliesInitialMigrationWithRequiredTablesAndConstraints() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");

        var tables = jdbcClient.sql("""
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN ('clients', 'charges', 'payments', 'charge_events')
                        """)
                .query(String.class)
                .list();

        assertThat(tables)
                .containsExactlyInAnyOrder("clients", "charges", "payments", "charge_events");

        var uniqueConstraints = jdbcClient.sql("""
                        SELECT constraint_name
                        FROM information_schema.table_constraints
                        WHERE table_schema = 'public'
                          AND table_name = 'payments'
                          AND constraint_type = 'UNIQUE'
                        """)
                .query(String.class)
                .list();

        assertThat(uniqueConstraints)
                .contains("uq_payments_charge_id", "uq_payments_idempotency_key");
    }
}
