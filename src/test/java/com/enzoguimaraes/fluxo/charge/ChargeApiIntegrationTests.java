package com.enzoguimaraes.fluxo.charge;

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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@Import(ChargeApiIntegrationTests.TestClockConfiguration.class)
class ChargeApiIntegrationTests {

    private static final Instant INITIAL_INSTANT = Instant.parse("2026-09-28T12:00:00Z");

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
    void resetDatabaseAndClock() {
        jdbcClient.sql("TRUNCATE TABLE clients CASCADE").update();
        clock.set(INITIAL_INSTANT);
    }

    @Test
    void createsChargeAndCreatedEventInPostgreSql() throws Exception {
        var clientId = insertClient("Ana", "ana@example.com");

        var response = postCharge(clientId, "  Assinatura de setembro  ", "149.90", "2026-09-28");
        var body = json(response);
        var chargeId = body.get("id").stringValue();

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location").orElseThrow())
                .endsWith("/api/v1/charges/" + chargeId);
        assertThat(body.get("description").stringValue()).isEqualTo("Assinatura de setembro");
        assertThat(body.get("amount").decimalValue()).isEqualByComparingTo("149.90");
        assertThat(body.get("currency").stringValue()).isEqualTo("BRL");
        assertThat(body.get("status").stringValue()).isEqualTo("PENDING");
        assertThat(body.get("condition").stringValue()).isEqualTo("PENDING");
        assertThat(body.get("client").get("id").stringValue()).isEqualTo(clientId.toString());

        assertThat(jdbcClient.sql("SELECT status FROM charges WHERE id = CAST(:id AS UUID)")
                .param("id", chargeId).query(String.class).single()).isEqualTo("PENDING");
        assertThat(jdbcClient.sql("SELECT type FROM charge_events WHERE charge_id = CAST(:id AS UUID)")
                .param("id", chargeId).query(String.class).single()).isEqualTo("CREATED");
    }

    @Test
    void rollsBackChargeWhenCreatedEventCannotBePersisted() throws Exception {
        var clientId = insertClient("Ana", "ana@example.com");
        jdbcClient.sql("""
                CREATE FUNCTION reject_charge_event() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'forced event failure';
                END;
                $$ LANGUAGE plpgsql
                """).update();
        jdbcClient.sql("""
                CREATE TRIGGER reject_charge_event
                BEFORE INSERT ON charge_events
                FOR EACH ROW EXECUTE FUNCTION reject_charge_event()
                """).update();

        try {
            var response = postCharge(clientId, "Assinatura", "10.00", "2026-09-28");

            assertProblem(response, 500, "INTERNAL_ERROR");
            assertThat(response.body()).doesNotContain("forced event failure", "stackTrace");
            assertThat(jdbcClient.sql("SELECT COUNT(*) FROM charges").query(Long.class).single())
                    .isZero();
        } finally {
            jdbcClient.sql("DROP TRIGGER reject_charge_event ON charge_events").update();
            jdbcClient.sql("DROP FUNCTION reject_charge_event()").update();
        }
    }

    @Test
    void enforcesAmountLimitsAndScale() throws Exception {
        var clientId = insertClient("Ana", "ana@example.com");

        for (var invalidAmount : new String[]{"0", "1000000.01", "1.001"}) {
            var response = postCharge(clientId, "Assinatura", invalidAmount, "2026-09-28");
            assertProblem(response, 400, "VALIDATION_ERROR");
            assertThat(errorFields(json(response))).contains("amount");
        }

        assertThat(postCharge(clientId, "Limite", "1000000.00", "2026-09-28").statusCode())
                .isEqualTo(201);
    }

    @Test
    void rejectsPastDueDateAndAcceptsToday() throws Exception {
        var clientId = insertClient("Ana", "ana@example.com");

        var past = postCharge(clientId, "Vencida", "10.00", "2026-09-27");

        assertProblem(past, 400, "VALIDATION_ERROR");
        assertThat(errorFields(json(past))).contains("dueDate");
        assertThat(postCharge(clientId, "Hoje", "10.00", "2026-09-28").statusCode())
                .isEqualTo(201);
    }

    @Test
    void returnsNotFoundForMissingClientAndCharge() throws Exception {
        var missingClient = postCharge(UUID.randomUUID(), "Assinatura", "10.00", "2026-09-28");
        var missingCharge = get("/api/v1/charges/" + UUID.randomUUID());

        assertProblem(missingClient, 404, "CLIENT_NOT_FOUND");
        assertProblem(missingCharge, 404, "CHARGE_NOT_FOUND");
    }

    @Test
    void recalculatesConditionAfterDayChangeAndFiltersBeforePagination() throws Exception {
        var clientA = insertClient("Ana", "ana@example.com");
        var clientB = insertClient("Bruno", "bruno@example.com");
        var dueTodayA = chargeId(postCharge(clientA, "A hoje", "10.00", "2026-09-28"));
        postCharge(clientA, "A amanha", "20.00", "2026-09-29");
        postCharge(clientB, "B hoje", "30.00", "2026-09-28");

        assertThat(total(get("/api/v1/charges?condition=PENDING&clientId=" + clientA)))
                .isEqualTo(2);

        clock.set(Instant.parse("2026-09-29T12:00:00Z"));

        var overdueForA = get("/api/v1/charges?condition=OVERDUE&clientId=" + clientA + "&page=0&size=1");
        assertThat(total(overdueForA)).isEqualTo(1);
        assertThat(json(overdueForA).get("content").get(0).get("id").stringValue())
                .isEqualTo(dueTodayA);
        assertThat(json(overdueForA).get("content").get(0).get("condition").stringValue())
                .isEqualTo("OVERDUE");
        assertThat(total(get("/api/v1/charges?condition=PENDING&clientId=" + clientA)))
                .isEqualTo(1);

        clock.set(Instant.parse("2026-09-30T12:00:00Z"));
        var firstPage = get("/api/v1/charges?condition=OVERDUE&clientId=" + clientA + "&page=0&size=1");
        var secondPage = get("/api/v1/charges?condition=OVERDUE&clientId=" + clientA + "&page=1&size=1");

        assertThat(total(firstPage)).isEqualTo(2);
        assertThat(json(firstPage).get("totalPages").asInt()).isEqualTo(2);
        assertThat(json(firstPage).get("content").size()).isEqualTo(1);
        assertThat(json(secondPage).get("content").size()).isEqualTo(1);
    }

    @Test
    void changesConditionAtMidnightInSaoPaulo() throws Exception {
        var clientId = insertClient("Ana", "ana@example.com");
        var chargeId = chargeId(postCharge(clientId, "Assinatura", "10.00", "2026-09-28"));

        clock.set(Instant.parse("2026-09-29T02:59:59Z"));
        var beforeMidnight = get("/api/v1/charges/" + chargeId);
        clock.set(Instant.parse("2026-09-29T03:00:00Z"));
        var atMidnight = get("/api/v1/charges/" + chargeId);

        assertThat(beforeMidnight.statusCode()).isEqualTo(200);
        assertThat(json(beforeMidnight).get("condition").stringValue()).isEqualTo("PENDING");
        assertThat(json(atMidnight).get("status").stringValue()).isEqualTo("PENDING");
        assertThat(json(atMidnight).get("condition").stringValue()).isEqualTo("OVERDUE");
    }

    @Test
    void filtersPaidAndCanceledPersistentStatuses() throws Exception {
        var clientId = insertClient("Ana", "ana@example.com");
        var paidId = chargeId(postCharge(clientId, "Paga", "10.00", "2026-09-28"));
        var canceledId = chargeId(postCharge(clientId, "Cancelada", "20.00", "2026-09-28"));
        jdbcClient.sql("UPDATE charges SET status = 'PAID' WHERE id = CAST(:id AS UUID)")
                .param("id", paidId).update();
        jdbcClient.sql("UPDATE charges SET status = 'CANCELED' WHERE id = CAST(:id AS UUID)")
                .param("id", canceledId).update();

        assertThat(onlyChargeId(get("/api/v1/charges?condition=PAID"))).isEqualTo(paidId);
        assertThat(onlyChargeId(get("/api/v1/charges?condition=CANCELED"))).isEqualTo(canceledId);
    }

    @Test
    void rejectsPageAboveMaximum() throws Exception {
        assertProblem(get("/api/v1/charges?size=101"), 400, "VALIDATION_ERROR");
    }

    private UUID insertClient(String name, String email) {
        var id = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO clients (id, name, email, created_at) VALUES (:id, :name, :email, :createdAt)")
                .param("id", id)
                .param("name", name)
                .param("email", email)
                .param("createdAt", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
        return id;
    }

    private HttpResponse<String> postCharge(
            UUID clientId,
            String description,
            String amount,
            String dueDate
    ) throws Exception {
        var payload = jsonMapper.writeValueAsString(Map.of(
                "clientId", clientId.toString(),
                "description", description,
                "amount", new BigDecimal(amount),
                "dueDate", dueDate
        ));
        var request = HttpRequest.newBuilder(uri("/api/v1/charges"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return jsonMapper.readTree(response.body());
    }

    private String chargeId(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(201);
        return json(response).get("id").stringValue();
    }

    private long total(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response).get("totalElements").asLong();
    }

    private String onlyChargeId(HttpResponse<String> response) throws Exception {
        assertThat(total(response)).isEqualTo(1);
        return json(response).get("content").get(0).get("id").stringValue();
    }

    private java.util.List<String> errorFields(JsonNode body) {
        return StreamSupport.stream(body.get("fieldErrors").spliterator(), false)
                .map(error -> error.get("field").stringValue())
                .toList();
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        var body = json(response);
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(body.get("code").stringValue()).isEqualTo(code);
        assertThat(body.get("traceId").stringValue()).isNotBlank();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(INITIAL_INSTANT, ZoneId.of("UTC"));
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

        void set(Instant value) {
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
