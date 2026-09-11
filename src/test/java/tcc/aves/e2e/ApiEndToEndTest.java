package tcc.aves.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import tcc.aves.security.JwtUtil;
import tcc.aves.support.AbstractDynamoDbLocalTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@Disabled("Requires the amazon/dynamodb-local Docker image, which this environment cannot pull/run reliably.")
@TestPropertySource(properties = {
        "jwt.secret=test-only-secret-key-not-for-production-use-1234567890",
        "jwt.expiration=60000"
})
class ApiEndToEndTest extends AbstractDynamoDbLocalTest {

    private static final String USERS_TABLE = "e2e-users";
    private static final String SPECIES_TABLE = "e2e-species";
    private static final String SIGHTINGS_TABLE = "e2e-sightings";
    private static final String TEST_SECRET = "test-only-secret-key-not-for-production-use-1234567890";

    @DynamicPropertySource
    static void dynamoDbProperties(DynamicPropertyRegistry registry) {
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        registry.add("aws.region", () -> REGION);
        registry.add("aws.dynamodb.endpoint-override", AbstractDynamoDbLocalTest::endpointOverride);
        registry.add("app.dynamodb.users-table", () -> USERS_TABLE);
        registry.add("app.dynamodb.species-table", () -> SPECIES_TABLE);
        registry.add("app.dynamodb.sightings-table", () -> SIGHTINGS_TABLE);
        registry.add("app.dynamodb.sightings-timeline-index", () -> "timeline-index");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeAll
    void setUpClient() {
        initializeClients();
    }

    @BeforeEach
    void resetTables() {
        recreateTables(USERS_TABLE, SPECIES_TABLE, SIGHTINGS_TABLE);
    }

    @Test
    void registerLoginAndManageOwnProfileWhileBlockingCrossUserAccess() throws Exception {
        JsonNode createdAlice = postJson("/api/users", """
                {
                  "name": "Alice",
                  "email": "alice@example.com",
                  "password": "secret1"
                }
                """, null, 201);
        String aliceId = createdAlice.get("id").asText();
        String aliceToken = login("alice@example.com", "secret1").get("token").asText();

        JsonNode createdBob = postJson("/api/users", """
                {
                  "name": "Bob",
                  "email": "bob@example.com",
                  "password": "secret2"
                }
                """, null, 201);
        String bobId = createdBob.get("id").asText();

        JsonNode ownProfile = getJson("/api/users/" + aliceId, aliceToken, 200);
        assertEquals("Alice", ownProfile.get("name").asText());
        assertEquals("alice@example.com", ownProfile.get("email").asText());

        JsonNode updatedProfile = putJson("/api/users/" + aliceId, """
                {
                  "name": "Alice Updated",
                  "email": "alice.updated@example.com"
                }
                """, aliceToken, 200);
        assertEquals("Alice Updated", updatedProfile.get("name").asText());
        assertEquals("alice.updated@example.com", updatedProfile.get("email").asText());

        assertEquals(403, getRaw("/api/users/" + bobId, aliceToken).statusCode());
        assertEquals(403, putRaw("/api/users/" + bobId, """
                {
                  "name": "Nope",
                  "email": "nope@example.com"
                }
                """, aliceToken).statusCode());
        assertEquals(403, deleteRaw("/api/users/" + bobId, aliceToken).statusCode());

        assertEquals(204, deleteRaw("/api/users/" + aliceId, aliceToken).statusCode());
        JsonNode deletedAccess = getJson("/api/users/" + aliceId, aliceToken, 401);
        assertEquals(401, deletedAccess.get("status").asInt());
    }

    @Test
    void invalidLoginTamperedTokenAndExpiredTokenReturnUnauthorized() throws Exception {
        String userId = postJson("/api/users", """
                {
                  "name": "Alice",
                  "email": "alice@example.com",
                  "password": "secret1"
                }
                """, null, 201).get("id").asText();

        JsonNode invalidLogin = postJson("/api/auth/login", """
                {
                  "email": "alice@example.com",
                  "password": "wrong"
                }
                """, null, 401);
        assertEquals("Email ou senha inválidos", invalidLogin.get("message").asText());

        String token = login("alice@example.com", "secret1").get("token").asText();
        String tamperedToken = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");
        assertEquals(401, getRaw("/api/sightings", tamperedToken).statusCode());

        String expiredToken = new JwtUtil(TEST_SECRET, -1).generateToken(userId, "alice@example.com");
        assertEquals(401, getRaw("/api/sightings", expiredToken).statusCode());
    }

    @Test
    void speciesCatalogIsPublicAndReferencedSpeciesCannotBeDeleted() throws Exception {
        JsonNode emptyCatalog = getJson("/api/species", null, 200);
        assertTrue(emptyCatalog.isArray());
        assertEquals(0, emptyCatalog.size());

        String token = registerAndLogin("owner@example.com", "secret1").token();
        JsonNode species = postJson("/api/species", """
                {
                  "name": "Arara",
                  "scientificName": "Ara ararauna",
                  "description": "Desc",
                  "tips": "Tips",
                  "imageUrl": "https://img/arara"
                }
                """, token, 201);
        String speciesId = species.get("id").asText();

        JsonNode updatedSpecies = putJson("/api/species/" + speciesId, """
                {
                  "name": "Arara Azul",
                  "scientificName": "Ara ararauna",
                  "description": "Nova",
                  "tips": "Novas",
                  "imageUrl": "https://img/arara2"
                }
                """, token, 200);
        assertEquals("Arara Azul", updatedSpecies.get("name").asText());

        postJson("/api/sightings", """
                {
                  "speciesIds": ["%s"],
                  "date": "2026-09-10",
                  "time": "10:15:00",
                  "imageUrl": "https://img/sighting"
                }
                """.formatted(speciesId), token, 201);

        JsonNode deleteInUse = deleteJson("/api/species/" + speciesId, token, 409);
        assertEquals(409, deleteInUse.get("status").asInt());
        assertEquals("Espécie não pode ser removida: está referenciada por avistamentos existentes", deleteInUse.get("message").asText());
    }

    @Test
    void sightingCrudLifecycleUsesFlatSpeciesArrayAndNoLegacyFields() throws Exception {
        Session user = registerAndLogin("sightings@example.com", "secret1");
        String firstSpeciesId = createSpecies(user.token(), "Arara", "Ara ararauna");
        String secondSpeciesId = createSpecies(user.token(), "Tucano", "Ramphastos toco");

        JsonNode created = postJson("/api/sightings", """
                {
                  "speciesIds": ["%s", "%s"],
                  "date": "2026-09-10",
                  "time": "10:15:00.500",
                  "imageUrl": "https://img/sighting/1"
                }
                """.formatted(firstSpeciesId, secondSpeciesId), user.token(), 201);
        String sightingId = created.get("id").asText();

        assertTrue(created.get("species").isArray());
        assertEquals(2, created.get("species").size());
        assertEquals(firstSpeciesId, created.get("species").get(0).get("id").asText());
        assertEquals(secondSpeciesId, created.get("species").get(1).get("id").asText());
        assertFalse(created.has("gender"));
        assertFalse(created.has("quantity"));
        assertFalse(created.has("owner"));

        JsonNode fetched = getJson("/api/sightings/" + sightingId, user.token(), 200);
        assertEquals(sightingId, fetched.get("id").asText());
        assertFalse(fetched.has("gender"));
        assertFalse(fetched.has("quantity"));
        assertFalse(fetched.has("owner"));

        JsonNode updated = putJson("/api/sightings/" + sightingId, """
                {
                  "speciesIds": ["%s"],
                  "date": "2026-09-11",
                  "time": "11:45:00",
                  "imageUrl": "https://img/sighting/updated"
                }
                """.formatted(secondSpeciesId), user.token(), 200);
        assertEquals(1, updated.get("species").size());
        assertEquals(secondSpeciesId, updated.get("species").get(0).get("id").asText());

        assertEquals(204, deleteRaw("/api/sightings/" + sightingId, user.token()).statusCode());
        JsonNode deleted = getJson("/api/sightings/" + sightingId, user.token(), 404);
        assertEquals(404, deleted.get("status").asInt());
    }

    @Test
    void sightingPaginationCursorDoesNotRepeatOrSkipItems() throws Exception {
        Session user = registerAndLogin("paging@example.com", "secret1");
        String speciesId = createSpecies(user.token(), "Arara", "Ara ararauna");

        String firstId = createSighting(user.token(), speciesId, "2026-09-01", "08:00:00");
        String secondId = createSighting(user.token(), speciesId, "2026-09-02", "08:00:00");
        String thirdId = createSighting(user.token(), speciesId, "2026-09-03", "08:00:00");

        JsonNode page1 = getJson("/api/sightings?limit=2", user.token(), 200);
        assertTrue(page1.get("hasMore").asBoolean());
        assertFalse(page1.get("nextCursor").isNull());
        assertEquals(List.of(thirdId, secondId), extractIds(page1));

        String nextCursor = URLEncoder.encode(page1.get("nextCursor").asText(), StandardCharsets.UTF_8);
        JsonNode page2 = getJson("/api/sightings?limit=2&cursor=" + nextCursor, user.token(), 200);
        assertFalse(page2.get("hasMore").asBoolean());
        assertEquals(List.of(firstId), extractIds(page2));

        Set<String> allIds = new LinkedHashSet<>();
        allIds.addAll(extractIds(page1));
        allIds.addAll(extractIds(page2));
        assertEquals(Set.of(firstId, secondId, thirdId), allIds);
    }

    @Test
    void sightingCombinedFiltersWorkAndPartialDateRangeIsRejected() throws Exception {
        Session user = registerAndLogin("filters@example.com", "secret1");
        String araraId = createSpecies(user.token(), "Arara", "Ara ararauna");
        String tucanoId = createSpecies(user.token(), "Tucano", "Ramphastos toco");

        String augustHalfSecond = createSighting(user.token(), List.of(araraId), "2026-08-31", "23:59:59.500");
        String augustWholeSecond = createSighting(user.token(), List.of(araraId, tucanoId), "2026-08-31", "23:59:59.000");
        createSighting(user.token(), List.of(tucanoId), "2026-09-01", "00:00:00");

        JsonNode filtered = getJson("/api/sightings?speciesId=" + araraId + "&from=2026-08-31&to=2026-08-31", user.token(), 200);
        assertEquals(List.of(augustHalfSecond, augustWholeSecond), extractIds(filtered));

        JsonNode invalidRange = getJson("/api/sightings?from=2026-08-31", user.token(), 400);
        assertEquals(400, invalidRange.get("status").asInt());
        assertEquals("Os filtros 'from' e 'to' devem ser informados juntos", invalidRange.get("message").asText());
    }

    private Session registerAndLogin(String email, String password) throws Exception {
        JsonNode created = postJson("/api/users", """
                {
                  "name": "%s",
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(email, email, password), null, 201);
        JsonNode login = login(email, password);
        return new Session(created.get("id").asText(), login.get("token").asText());
    }

    private JsonNode login(String email, String password) throws Exception {
        return postJson("/api/auth/login", """
                {
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(email, password), null, 200);
    }

    private String createSpecies(String token, String name, String scientificName) throws Exception {
        return postJson("/api/species", """
                {
                  "name": "%s",
                  "scientificName": "%s",
                  "description": "Desc %s",
                  "tips": "Tips %s",
                  "imageUrl": "https://img/%s"
                }
                """.formatted(name, scientificName, name, name, name.toLowerCase()), token, 201).get("id").asText();
    }

    private String createSighting(String token, String speciesId, String date, String time) throws Exception {
        return createSighting(token, List.of(speciesId), date, time);
    }

    private String createSighting(String token, List<String> speciesIds, String date, String time) throws Exception {
        String speciesArray = speciesIds.stream().map(id -> "\"" + id + "\"").reduce((a, b) -> a + ", " + b).orElse("");
        return postJson("/api/sightings", """
                {
                  "speciesIds": [%s],
                  "date": "%s",
                  "time": "%s",
                  "imageUrl": "https://img/sighting/%s"
                }
                """.formatted(speciesArray, date, time, date.replace("-", "") + time.replace(":", "").replace(".", "")), token, 201)
                .get("id").asText();
    }

    private List<String> extractIds(JsonNode page) {
        return java.util.stream.StreamSupport.stream(page.get("items").spliterator(), false)
                .map(item -> item.get("id").asText())
                .toList();
    }

    private JsonNode getJson(String path, String token, int expectedStatus) throws Exception {
        HttpResponse<String> response = getRaw(path, token);
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return response.body().isBlank() ? objectMapper.nullNode() : objectMapper.readTree(response.body());
    }

    private HttpResponse<String> getRaw(String path, String token) throws Exception {
        return send("GET", path, null, token);
    }

    private JsonNode postJson(String path, String body, String token, int expectedStatus) throws Exception {
        HttpResponse<String> response = send("POST", path, body, token);
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return response.body().isBlank() ? objectMapper.nullNode() : objectMapper.readTree(response.body());
    }

    private JsonNode putJson(String path, String body, String token, int expectedStatus) throws Exception {
        HttpResponse<String> response = putRaw(path, body, token);
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> putRaw(String path, String body, String token) throws Exception {
        return send("PUT", path, body, token);
    }

    private JsonNode deleteJson(String path, String token, int expectedStatus) throws Exception {
        HttpResponse<String> response = deleteRaw(path, token);
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> deleteRaw(String path, String token) throws Exception {
        return send("DELETE", path, null, token);
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private record Session(String userId, String token) {
    }
}
