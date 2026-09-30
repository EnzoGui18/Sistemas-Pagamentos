package com.enzoguimaraes.fluxo.dashboard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@Import(DashboardApiIntegrationTests.TestClockConfiguration.class)
class DashboardApiIntegrationTests {

    private static final Instant BEFORE_MIDNIGHT_IN_SAO_PAULO = Instant.parse("2026-09-30T02:59:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock.setInstant(BEFORE_MIDNIGHT_IN_SAO_PAULO);
        jdbcClient.sql("TRUNCATE TABLE clients CASCADE").update();
    }

    @Test
    void summarizesEveryConditionAndExcludesCanceledFromReceivable() throws Exception {
        insertCharge("100.00", LocalDate.of(2026, 9, 29), "PENDING");
        insertCharge("40.50", LocalDate.of(2026, 9, 28), "PENDING");
        insertCharge("80.00", LocalDate.of(2026, 9, 20), "PAID");
        insertCharge("999.99", LocalDate.of(2026, 9, 20), "CANCELED");

        var response = getSummary();
        var body = jsonMapper.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.get("referenceDate").stringValue()).isEqualTo("2026-09-29");
        assertMetric(body.get("pending"), 1, "100.00");
        assertMetric(body.get("overdue"), 1, "40.50");
        assertMetric(body.get("paid"), 1, "80.00");
        assertMetric(body.get("canceled"), 1, "999.99");
        assertMetric(body.get("receivable"), 2, "140.50");
    }

    @Test
    void movesPendingChargeToOverdueAfterBusinessDayChanges() throws Exception {
        insertCharge("125.25", LocalDate.of(2026, 9, 29), "PENDING");

        var before = jsonMapper.readTree(getSummary().body());
        clock.setInstant(Instant.parse("2026-09-30T03:01:00Z"));
        var after = jsonMapper.readTree(getSummary().body());

        assertThat(before.get("referenceDate").stringValue()).isEqualTo("2026-09-29");
        assertMetric(before.get("pending"), 1, "125.25");
        assertMetric(before.get("overdue"), 0, "0.00");
        assertThat(after.get("referenceDate").stringValue()).isEqualTo("2026-09-30");
        assertMetric(after.get("pending"), 0, "0.00");
        assertMetric(after.get("overdue"), 1, "125.25");
        assertMetric(after.get("receivable"), 1, "125.25");
    }

    private HttpResponse<String> getSummary() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/v1/dashboard/summary"))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void insertCharge(String amount, LocalDate dueDate, String status) {
        var clientId = UUID.randomUUID();
        var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbcClient.sql("INSERT INTO clients (id, name, email, created_at) VALUES (:id, 'Test', :email, :now)")
                .param("id", clientId)
                .param("email", clientId + "@example.com")
                .param("now", now)
                .update();
        jdbcClient.sql("""
                        INSERT INTO charges
                            (id, client_id, description, amount, currency, due_date, status,
                             created_at, updated_at, version)
                        VALUES
                            (:id, :clientId, 'Dashboard charge', :amount, 'BRL', :dueDate, :status,
                             :now, :now, 0)
                        """)
                .param("id", UUID.randomUUID())
                .param("clientId", clientId)
                .param("amount", new BigDecimal(amount))
                .param("dueDate", dueDate)
                .param("status", status)
                .param("now", now)
                .update();
    }

    private void assertMetric(JsonNode metric, long count, String amount) {
        assertThat(metric.get("count").longValue()).isEqualTo(count);
        assertThat(metric.get("amount").decimalValue()).isEqualByComparingTo(amount);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {

        @Bean
        @Primary
        MutableClock fixedClock() {
            return new MutableClock(BEFORE_MIDNIGHT_IN_SAO_PAULO, ZoneId.of("UTC"));
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this(new AtomicReference<>(instant), zone);
        }

        private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void setInstant(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId targetZone) {
            return zone.equals(targetZone) ? this : new MutableClock(instant, targetZone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
