package com.enzoguimaraes.fluxo.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ClientApiIntegrationTests {

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
    void clearClients() {
        jdbcClient.sql("TRUNCATE TABLE clients CASCADE").update();
    }

    @Test
    void createsClientWithServerFieldsAndPersistsNormalizedEmail() throws Exception {
        var response = postClient("  Ana Silva  ", "ANA.SILVA@EXAMPLE.COM");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/json");

        var body = json(response);
        var id = body.get("id").stringValue();
        assertThat(response.headers().firstValue("Location").orElseThrow())
                .endsWith("/api/v1/clients/" + id);
        assertThat(body.get("name").stringValue()).isEqualTo("Ana Silva");
        assertThat(body.get("email").stringValue()).isEqualTo("ana.silva@example.com");
        assertThat(Instant.parse(body.get("createdAt").stringValue())).isBeforeOrEqualTo(Instant.now());

        var persistedEmail = jdbcClient.sql("SELECT email FROM clients WHERE id = CAST(:id AS UUID)")
                .param("id", id)
                .query(String.class)
                .single();
        assertThat(persistedEmail).isEqualTo("ana.silva@example.com");
    }

    @Test
    void searchesByNameOrEmailIgnoringCase() throws Exception {
        postClient("Ana Silva", "ana@example.com");
        postClient("Bruno Lima", "bruno@empresa.com");

        var byName = get("/api/v1/clients?search=ANA&page=0&size=20");
        var byEmail = get("/api/v1/clients?search=EMPRESA&page=0&size=20");

        assertThat(byName.statusCode()).isEqualTo(200);
        assertThat(json(byName).get("content").get(0).get("name").stringValue()).isEqualTo("Ana Silva");
        assertThat(json(byEmail).get("content").get(0).get("name").stringValue()).isEqualTo("Bruno Lima");
    }

    @Test
    void returnsRequestedPageWithMetadata() throws Exception {
        postClient("Cliente A", "a@example.com");
        postClient("Cliente B", "b@example.com");
        postClient("Cliente C", "c@example.com");

        var response = get("/api/v1/clients?page=1&size=2");
        var body = json(response);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.get("content").size()).isEqualTo(1);
        assertThat(body.get("page").asInt()).isEqualTo(1);
        assertThat(body.get("size").asInt()).isEqualTo(2);
        assertThat(body.get("totalElements").asLong()).isEqualTo(3);
        assertThat(body.get("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidBodyWithProblemDetailsAndFieldErrors() throws Exception {
        var response = postClient("", "invalid-email");
        var body = json(response);

        assertProblem(response, body, 400, "VALIDATION_ERROR");
        assertThat(errorFields(body)).contains("name", "email");
        assertThat(body.get("fieldErrors").size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void rejectsDuplicateNormalizedEmail() throws Exception {
        postClient("Primeiro", "CLIENTE@EXAMPLE.COM");

        var response = postClient("Segundo", "cliente@example.com");
        var body = json(response);

        assertProblem(response, body, 409, "CLIENT_EMAIL_ALREADY_EXISTS");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM clients")
                .query(Long.class)
                .single()).isEqualTo(1L);
    }

    @Test
    void trimsEmailBeforeValidatingAndPersisting() throws Exception {
        var response = postClient("Ana Silva", "  ANA.SILVA@EXAMPLE.COM  ");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response).get("email").stringValue()).isEqualTo("ana.silva@example.com");
    }

    @Test
    void rejectsPageSizeAboveMaximum() throws Exception {
        var response = get("/api/v1/clients?page=0&size=101");
        var body = json(response);

        assertProblem(response, body, 400, "VALIDATION_ERROR");
        assertThat(errorFields(body)).contains("size");
    }

    @Test
    void returnsStandardProblemsForUnknownResourceAndUnsupportedMethod() throws Exception {
        var unknown = get("/api/v1/unknown-resource");
        var unsupported = httpClient.send(
                HttpRequest.newBuilder(uri("/api/v1/dashboard/summary"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertProblem(unknown, json(unknown), 404, "RESOURCE_NOT_FOUND");
        assertProblem(unsupported, json(unsupported), 405, "METHOD_NOT_ALLOWED");
        assertThat(unsupported.headers().firstValue("Allow").orElseThrow()).contains("GET");
    }

    private HttpResponse<String> postClient(String name, String email) throws Exception {
        var payload = jsonMapper.writeValueAsString(new CreateClientRequest(name, email));
        var request = HttpRequest.newBuilder(uri("/api/v1/clients"))
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

    private Set<String> errorFields(JsonNode body) {
        return StreamSupport.stream(body.get("fieldErrors").spliterator(), false)
                .map(error -> error.get("field").stringValue())
                .collect(Collectors.toSet());
    }

    private void assertProblem(
            HttpResponse<String> response,
            JsonNode body,
            int expectedStatus,
            String expectedCode
    ) {
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(body.get("type").stringValue()).isEqualTo("about:blank");
        assertThat(body.get("status").asInt()).isEqualTo(expectedStatus);
        assertThat(body.get("code").stringValue()).isEqualTo(expectedCode);
        assertThat(body.get("traceId").stringValue()).isNotBlank();
        assertThat(response.headers().firstValue("X-Trace-Id").orElseThrow())
                .isEqualTo(body.get("traceId").stringValue());
    }
}
