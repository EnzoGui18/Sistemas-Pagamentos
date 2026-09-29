package com.enzoguimaraes.fluxo.payment;

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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@Import(PaymentAndCancellationIntegrationTests.TestClockConfiguration.class)
class PaymentAndCancellationIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearDatabase() {
        jdbcClient.sql("TRUNCATE TABLE clients CASCADE").update();
    }

    @Test
    void paysPendingChargeWithServerAmountAndCreatesPaidEvent() throws Exception {
        var chargeId = insertPendingCharge("149.90", LocalDate.of(2026, 9, 30));

        var response = postPayment(chargeId, "payment-normal");
        var body = json(response);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location").orElseThrow())
                .endsWith("/api/v1/charges/" + chargeId + "/payments/" + body.get("id").stringValue());
        assertThat(body.get("chargeId").stringValue()).isEqualTo(chargeId.toString());
        assertThat(body.get("amount").decimalValue()).isEqualByComparingTo("149.90");
        assertThat(body.get("currency").stringValue()).isEqualTo("BRL");
        assertThat(chargeStatus(chargeId)).isEqualTo("PAID");
        assertThat(chargeVersion(chargeId)).isEqualTo(1L);
        assertThat(eventTypes(chargeId)).containsExactly("CREATED", "PAID");
    }

    @Test
    void paysOverdueChargeWithoutPersistingOverdueStatus() throws Exception {
        var chargeId = insertPendingCharge("75.00", LocalDate.of(2026, 9, 28));

        var response = postPayment(chargeId, "payment-overdue");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(chargeStatus(chargeId)).isEqualTo("PAID");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM charges WHERE status = 'OVERDUE'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void replaysCompletedPaymentForSameKeyAndCharge() throws Exception {
        var chargeId = insertPendingCharge("25.00", LocalDate.of(2026, 9, 29));

        var first = postPayment(chargeId, "same-key");
        var replay = postPayment(chargeId, "same-key");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json(replay).get("id").stringValue()).isEqualTo(json(first).get("id").stringValue());
        assertThat(paymentCount(chargeId)).isEqualTo(1);
        assertThat(eventCount(chargeId, "PAID")).isEqualTo(1);
    }

    @Test
    void rejectsKeyAlreadyUsedByAnotherCharge() throws Exception {
        var firstCharge = insertPendingCharge("10.00", LocalDate.of(2026, 9, 29));
        var secondCharge = insertPendingCharge("20.00", LocalDate.of(2026, 9, 29));
        assertThat(postPayment(firstCharge, "shared-key").statusCode()).isEqualTo(201);

        var conflict = postPayment(secondCharge, "shared-key");

        assertProblem(conflict, 409, "IDEMPOTENCY_KEY_CONFLICT");
        assertThat(chargeStatus(secondCharge)).isEqualTo("PENDING");
        assertThat(paymentCount(secondCharge)).isZero();
    }

    @Test
    void rejectsMissingKeyAndNewTransitionsAfterTerminalState() throws Exception {
        var paidCharge = insertPendingCharge("10.00", LocalDate.of(2026, 9, 29));
        var canceledCharge = insertPendingCharge("20.00", LocalDate.of(2026, 9, 29));

        var missingKey = postPayment(paidCharge, null);
        assertProblem(missingKey, 400, "VALIDATION_ERROR");
        assertThat(postPayment(paidCharge, "pay-once").statusCode()).isEqualTo(201);
        assertProblem(postCancellation(paidCharge), 409, "INVALID_CHARGE_TRANSITION");

        assertThat(postCancellation(canceledCharge).statusCode()).isEqualTo(200);
        assertProblem(postPayment(canceledCharge, "after-cancel"), 409, "INVALID_CHARGE_TRANSITION");
        assertProblem(postCancellation(canceledCharge), 409, "INVALID_CHARGE_TRANSITION");
    }

    @Test
    void cancelsOverdueChargeAndReturnsOrderedHistory() throws Exception {
        var chargeId = insertPendingCharge("35.00", LocalDate.of(2026, 9, 28));

        var cancellation = postCancellation(chargeId);
        var history = get("/api/v1/charges/" + chargeId + "/events");

        assertThat(cancellation.statusCode()).isEqualTo(200);
        assertThat(json(cancellation).get("status").stringValue()).isEqualTo("CANCELED");
        assertThat(json(cancellation).get("condition").stringValue()).isEqualTo("CANCELED");
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(eventTypes(history)).containsExactly("CREATED", "CANCELED");
        assertThat(paymentCount(chargeId)).isZero();
        assertThat(chargeVersion(chargeId)).isEqualTo(1L);
    }

    @Test
    void rollsBackPaymentWhenPaidEventFails() throws Exception {
        var chargeId = insertPendingCharge("50.00", LocalDate.of(2026, 9, 29));
        installRejectingEventTrigger("PAID");

        try {
            var response = postPayment(chargeId, "rollback-payment");

            assertProblem(response, 500, "INTERNAL_ERROR");
            assertThat(response.body()).doesNotContain("forced event failure", "stackTrace");
            assertThat(chargeStatus(chargeId)).isEqualTo("PENDING");
            assertThat(chargeVersion(chargeId)).isZero();
            assertThat(paymentCount(chargeId)).isZero();
            assertThat(eventCount(chargeId, "PAID")).isZero();
        } finally {
            removeRejectingEventTrigger();
        }
    }

    @Test
    void rollsBackCancellationWhenCanceledEventFails() throws Exception {
        var chargeId = insertPendingCharge("60.00", LocalDate.of(2026, 9, 29));
        installRejectingEventTrigger("CANCELED");

        try {
            var response = postCancellation(chargeId);

            assertProblem(response, 500, "INTERNAL_ERROR");
            assertThat(chargeStatus(chargeId)).isEqualTo("PENDING");
            assertThat(chargeVersion(chargeId)).isZero();
            assertThat(eventCount(chargeId, "CANCELED")).isZero();
        } finally {
            removeRejectingEventTrigger();
        }
    }

    @Test
    void concurrentPaymentsWithSameKeyProduceCreatedAndReplay() throws Exception {
        var chargeId = insertPendingCharge("70.00", LocalDate.of(2026, 9, 29));

        var responses = sendTogether(
                paymentRequest(chargeId, "race-same-key"),
                paymentRequest(chargeId, "race-same-key")
        );

        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 201);
        assertThat(json(responses.get(0)).get("id").stringValue())
                .isEqualTo(json(responses.get(1)).get("id").stringValue());
        assertThat(paymentCount(chargeId)).isEqualTo(1);
        assertThat(eventCount(chargeId, "PAID")).isEqualTo(1);
    }

    @Test
    void concurrentPaymentsWithDifferentKeysHaveSingleWinner() throws Exception {
        var chargeId = insertPendingCharge("80.00", LocalDate.of(2026, 9, 29));

        var responses = sendTogether(
                paymentRequest(chargeId, "race-key-a"),
                paymentRequest(chargeId, "race-key-b")
        );

        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
        assertThat(paymentCount(chargeId)).isEqualTo(1);
        assertThat(eventCount(chargeId, "PAID")).isEqualTo(1);
        assertThat(chargeVersion(chargeId)).isEqualTo(1L);
    }

    @Test
    void concurrentPaymentAndCancellationLeaveOneConsistentWinner() throws Exception {
        var chargeId = insertPendingCharge("90.00", LocalDate.of(2026, 9, 29));

        var responses = sendTogether(
                paymentRequest(chargeId, "race-payment-cancel"),
                cancellationRequest(chargeId)
        );

        assertThat(responses).extracting(HttpResponse::statusCode)
                .satisfiesExactlyInAnyOrder(
                        status -> assertThat(status).isIn(200, 201),
                        status -> assertThat(status).isEqualTo(409)
                );
        assertThat(chargeVersion(chargeId)).isEqualTo(1L);
        if (chargeStatus(chargeId).equals("PAID")) {
            assertThat(paymentCount(chargeId)).isEqualTo(1);
            assertThat(eventCount(chargeId, "PAID")).isEqualTo(1);
            assertThat(eventCount(chargeId, "CANCELED")).isZero();
        } else {
            assertThat(chargeStatus(chargeId)).isEqualTo("CANCELED");
            assertThat(paymentCount(chargeId)).isZero();
            assertThat(eventCount(chargeId, "PAID")).isZero();
            assertThat(eventCount(chargeId, "CANCELED")).isEqualTo(1);
        }
    }

    private UUID insertPendingCharge(String amount, LocalDate dueDate) {
        var clientId = UUID.randomUUID();
        var chargeId = UUID.randomUUID();
        var createdAt = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        jdbcClient.sql("INSERT INTO clients (id, name, email, created_at) VALUES (:id, 'Test', :email, :now)")
                .param("id", clientId)
                .param("email", clientId + "@example.com")
                .param("now", createdAt)
                .update();
        jdbcClient.sql("""
                INSERT INTO charges
                    (id, client_id, description, amount, currency, due_date, status, created_at, updated_at, version)
                VALUES
                    (:id, :clientId, 'Test charge', :amount, 'BRL', :dueDate, 'PENDING', :now, :now, 0)
                """)
                .param("id", chargeId)
                .param("clientId", clientId)
                .param("amount", new BigDecimal(amount))
                .param("dueDate", dueDate)
                .param("now", createdAt)
                .update();
        jdbcClient.sql("""
                INSERT INTO charge_events (id, charge_id, type, occurred_at)
                VALUES (:id, :chargeId, 'CREATED', :now)
                """)
                .param("id", UUID.randomUUID())
                .param("chargeId", chargeId)
                .param("now", createdAt)
                .update();
        return chargeId;
    }

    private HttpResponse<String> postPayment(UUID chargeId, String key) throws Exception {
        return send(paymentRequest(chargeId, key));
    }

    private HttpRequest paymentRequest(UUID chargeId, String key) {
        var builder = HttpRequest.newBuilder(uri("/api/v1/charges/" + chargeId + "/payments"))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        return builder.build();
    }

    private HttpResponse<String> postCancellation(UUID chargeId) throws Exception {
        return send(cancellationRequest(chargeId));
    }

    private HttpRequest cancellationRequest(UUID chargeId) {
        return HttpRequest.newBuilder(uri("/api/v1/charges/" + chargeId + "/cancellation"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private List<HttpResponse<String>> sendTogether(HttpRequest first, HttpRequest second) throws Exception {
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstFuture = asyncSend(first, start, executor);
            var secondFuture = asyncSend(second, start, executor);
            start.countDown();
            return List.of(firstFuture.get(), secondFuture.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private CompletableFuture<HttpResponse<String>> asyncSend(
            HttpRequest request,
            CountDownLatch start,
            java.util.concurrent.Executor executor
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                start.await();
                return send(request);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }, executor);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return jsonMapper.readTree(response.body());
    }

    private String chargeStatus(UUID chargeId) {
        return jdbcClient.sql("SELECT status FROM charges WHERE id = :id")
                .param("id", chargeId).query(String.class).single();
    }

    private long chargeVersion(UUID chargeId) {
        return jdbcClient.sql("SELECT version FROM charges WHERE id = :id")
                .param("id", chargeId).query(Long.class).single();
    }

    private long paymentCount(UUID chargeId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM payments WHERE charge_id = :id")
                .param("id", chargeId).query(Long.class).single();
    }

    private long eventCount(UUID chargeId, String type) {
        return jdbcClient.sql("SELECT COUNT(*) FROM charge_events WHERE charge_id = :id AND type = :type")
                .param("id", chargeId).param("type", type).query(Long.class).single();
    }

    private List<String> eventTypes(UUID chargeId) throws Exception {
        return eventTypes(get("/api/v1/charges/" + chargeId + "/events"));
    }

    private List<String> eventTypes(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response).valueStream()
                .map(event -> event.get("type").stringValue())
                .toList();
    }

    private void installRejectingEventTrigger(String eventType) {
        jdbcClient.sql("""
                CREATE FUNCTION reject_transition_event() RETURNS trigger AS $$
                BEGIN
                    IF NEW.type = '%s' THEN
                        RAISE EXCEPTION 'forced event failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(eventType)).update();
        jdbcClient.sql("""
                CREATE TRIGGER reject_transition_event
                BEFORE INSERT ON charge_events
                FOR EACH ROW EXECUTE FUNCTION reject_transition_event()
                """).update();
    }

    private void removeRejectingEventTrigger() {
        jdbcClient.sql("DROP TRIGGER reject_transition_event ON charge_events").update();
        jdbcClient.sql("DROP FUNCTION reject_transition_event()").update();
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
        Clock fixedClock() {
            return new MutableClock(NOW, ZoneId.of("UTC"));
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
