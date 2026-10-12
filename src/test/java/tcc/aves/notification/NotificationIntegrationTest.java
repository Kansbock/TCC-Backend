package tcc.aves.notification;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.junit.jupiter.Testcontainers;
import tcc.aves.repository.NotificationRepository;
import tcc.aves.repository.NotificationRepository.*;
import tcc.aves.repository.UserRecord;
import tcc.aves.repository.UserRepository;
import tcc.aves.security.JwtUtil;
import tcc.aves.repository.dynamodb.model.NotificationJobItem;
import tcc.aves.repository.dynamodb.NotificationRepositoryDynamoDbAdapter;
import tcc.aves.support.AbstractDynamoDbLocalTest;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"jwt.secret=synthetic-notification-test-only-key-1234567890",
                "app.notifications.runner-user-id=synthetic-runner", "app.notifications.processing-enabled=true"})
@Import(NotificationIntegrationTest.TestGateway.class)
class NotificationIntegrationTest extends AbstractDynamoDbLocalTest {
    @TestConfiguration
    static class TestGateway {
        @Bean @Primary FcmGateway syntheticFcm() {
            return (event, device) -> new FcmGateway.Result(FcmGateway.Outcome.ACCEPTED,
                    "synthetic-message", null, Duration.ZERO);
        }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DYNAMODB_LOCAL.start();
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        registry.add("aws.dynamodb.endpoint-override", AbstractDynamoDbLocalTest::endpointOverride);
        registry.add("app.dynamodb.users-table", () -> "notification-test-users");
        registry.add("app.dynamodb.species-table", () -> "notification-test-species");
        registry.add("app.dynamodb.sightings-table", () -> "notification-test-sightings");
    }

    @Autowired private NotificationRepository repository;
    @Autowired private UserRepository users;
    @Autowired private JwtUtil jwt;
    @LocalServerPort private int port;
    private final Instant start = Instant.parse("2026-01-01T00:00:00Z");
    private UserRecord alice;
    private UserRecord bob;

    @BeforeAll
    void initialise() { initializeClients(); }

    @BeforeEach
    void reset() {
        recreateTables("notification-test-users", "notification-test-species", "notification-test-sightings");
        alice = users.create("Alice", "alice@example.test", "synthetic-hash");
        bob = users.create("Bob", "bob@example.test", "synthetic-hash");
    }

    @AfterAll
    void close() { dynamoDbClient.close(); }

    @Test
    void concurrentReplayAndReadHaveExactlyOneCounterChange() throws Exception {
        repository.subscribe(alice.id(), start);
        repository.subscribe(alice.id(), start.plusSeconds(10));
        assertEquals(start, repository.preferences(alice.id()).startedAt());
        Event event = new Event("sighting-1", start.plusSeconds(1));
        try (var workers = Executors.newFixedThreadPool(4)) {
            var creations = new ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i = 0; i < 12; i++) creations.add(() -> repository.createNotice(alice.id(), event));
            long created = 0;
            for (var result : workers.invokeAll(creations)) if (result.get()) created++;
            assertEquals(1, created);
            assertEquals(1, repository.preferences(alice.id()).unreadCount());
            var reads = new ArrayList<java.util.concurrent.Callable<Notice>>();
            for (int i = 0; i < 12; i++) reads.add(() -> repository.markRead(alice.id(), "sighting-1", start.plusSeconds(2)));
            for (var result : workers.invokeAll(reads)) assertNotNull(result.get().readAt());
        }
        assertEquals(0, repository.preferences(alice.id()).unreadCount());
        assertFalse(repository.createNotice(alice.id(), event));
        assertNotNull(repository.inbox(alice.id(), 20, null).items().getFirst().readAt());
    }

    @Test
    void paginationTemporalParticipationAndDeletedAccountsStayIsolated() {
        repository.subscribe(alice.id(), start);
        repository.subscribe(bob.id(), start.plusSeconds(10));
        Event first = new Event("sighting-1", start.plusSeconds(1));
        assertFalse(repository.createNotice(bob.id(), first));
        assertEquals(1, repository.participants(first, null).items().size());
        for (int i = 1; i <= 3; i++) repository.createNotice(alice.id(), new Event("sighting-" + i, start.plusSeconds(i)));
        var page = repository.inbox(alice.id(), 2, null);
        assertEquals(List.of("sighting-3", "sighting-2"), page.items().stream().map(Notice::id).toList());
        assertEquals(List.of("sighting-1"), repository.inbox(alice.id(), 2, page.nextCursor()).items().stream().map(Notice::id).toList());
        assertThrows(IllegalArgumentException.class, () -> repository.inbox(bob.id(), 2, page.nextCursor()));
        assertThrows(IllegalArgumentException.class, () -> repository.inbox(alice.id(), 2, "bad-cursor"));
        assertThrows(tcc.aves.exception.ResourceNotFoundException.class, () -> repository.markRead(bob.id(), "sighting-1", start));
        users.delete(alice.id());
        assertFalse(repository.createNotice(alice.id(), new Event("sighting-4", start.plusSeconds(4))));
    }

    @Test
    void tokenTransferRotationAndDelayedUnregisteredCannotDisableNewGeneration() {
        repository.subscribe(alice.id(), start);
        repository.subscribe(bob.id(), start);
        repository.registerDevice(alice.id(), "installation-a", "synthetic-token-1234567890");
        Device old = repository.devices(alice.id(), null).items().getFirst();
        repository.registerDevice(alice.id(), "installation-a", "synthetic-token-1234567890");
        assertEquals(old.generation(), repository.devices(alice.id(), null).items().getFirst().generation());
        repository.registerDevice(bob.id(), "installation-b", old.token());
        assertTrue(repository.devices(alice.id(), null).items().isEmpty());
        assertTrue(repository.eligible(repository.devices(bob.id(), null).items().getFirst()));
        repository.registerDevice(alice.id(), "installation-a", "synthetic-new-alice-token-12345");
        repository.deactivateGeneration(old);
        assertTrue(repository.eligible(repository.devices(alice.id(), null).items().getFirst()));
        repository.registerDevice(bob.id(), "installation-b", "synthetic-rotated-token-12345");
        repository.deactivateGeneration(old);
        assertTrue(repository.eligible(repository.devices(bob.id(), null).items().getFirst()));
        repository.setPreference(bob.id(), false);
        assertFalse(repository.eligible(repository.devices(bob.id(), null).items().getFirst()));
        repository.deactivateDevice(bob.id(), "installation-b");
        repository.deactivateDevice(bob.id(), "installation-b");
        assertTrue(repository.devices(bob.id(), null).items().isEmpty());
    }

    @Test
    void dispatchPersistsHistoryBeforeRetryRespectsDueTimeAndDoesNotRepublishAcceptance() {
        repository.subscribe(alice.id(), start);
        repository.registerDevice(alice.id(), "install-a", "synthetic-token-1234567890");
        Device device = repository.devices(alice.id(), null).items().getFirst();
        Event event = new Event("sighting-1", start.plusSeconds(1));
        AtomicInteger sent = new AtomicInteger();
        FcmGateway gateway = (ignored, ignoredDevice) -> sent.incrementAndGet() == 1
                ? new FcmGateway.Result(FcmGateway.Outcome.RETRYABLE, null, "HTTP_429", Duration.ofSeconds(120))
                : new FcmGateway.Result(FcmGateway.Outcome.ACCEPTED, "synthetic-message", null, Duration.ZERO);
        NotificationDispatcher first = new NotificationDispatcher(repository, gateway, Clock.fixed(event.createdAt(), ZoneOffset.UTC));
        assertThrows(IllegalStateException.class, () -> first.dispatch(event, () -> 60000));
        assertEquals(1, repository.preferences(alice.id()).unreadCount());
        assertEquals(event.createdAt().plusSeconds(120), repository.attempt(event, device).nextAttemptAt());
        assertThrows(IllegalStateException.class, () -> first.dispatch(event, () -> 60000));
        assertEquals(1, sent.get());
        NotificationDispatcher retry = new NotificationDispatcher(repository, gateway, Clock.fixed(event.createdAt().plusSeconds(121), ZoneOffset.UTC));
        retry.dispatch(event, () -> 60000);
        retry.dispatch(event, () -> 60000);
        assertEquals(2, sent.get());
        assertEquals(1, repository.preferences(alice.id()).unreadCount());
        assertEquals("ACCEPTED", repository.attempt(event, device).status());
    }

    @Test
    void durableQueueSurvivesRetryAndContinuesWithoutNewSightings() {
        repository.subscribe(alice.id(), start);
        repository.registerDevice(alice.id(), "install-a", "synthetic-token-1234567890");
        Event event = new Event("queued-event", start.plusSeconds(1));
        enqueue(event);
        AtomicInteger sent = new AtomicInteger();
        FcmGateway fcm = (e, d) -> sent.incrementAndGet() == 1
                ? new FcmGateway.Result(FcmGateway.Outcome.RETRYABLE, null, "HTTP_429", Duration.ofSeconds(120))
                : new FcmGateway.Result(FcmGateway.Outcome.ACCEPTED, "synthetic-message", null, Duration.ZERO);
        var now = Clock.fixed(event.createdAt(), ZoneOffset.UTC);
        assertEquals(new NotificationJobProcessor.Batch(0, 1, 0),
                new NotificationJobProcessor(repository, fcm, now).process(() -> 60000));
        assertTrue(repository.dueJobs(event.createdAt().plusSeconds(119), 10).isEmpty());
        assertEquals(1, repository.preferences(alice.id()).unreadCount());
        assertEquals(new NotificationJobProcessor.Batch(1, 0, 0),
                new NotificationJobProcessor(repository, fcm,
                        Clock.fixed(event.createdAt().plusSeconds(121), ZoneOffset.UTC)).process(() -> 60000));
        assertTrue(repository.dueJobs(event.createdAt().plusSeconds(3600), 10).isEmpty());
        assertEquals(2, sent.get());
        assertEquals(1, repository.preferences(alice.id()).unreadCount());
    }

    @Test
    void terminalFailureAndDeadlinePreserveStateWithoutLosingWork() {
        repository.subscribe(alice.id(), start);
        repository.registerDevice(alice.id(), "install-a", "synthetic-token-1234567890");
        Event event = new Event("failed-event", start.plusSeconds(1));
        enqueue(event);
        FcmGateway invalid = (e, d) -> new FcmGateway.Result(FcmGateway.Outcome.INVALID_PAYLOAD, null, "INVALID_PAYLOAD", Duration.ZERO);
        var processor = new NotificationJobProcessor(repository, invalid, Clock.fixed(event.createdAt(), ZoneOffset.UTC));
        assertEquals(new NotificationJobProcessor.Batch(0, 1, 0), processor.process(() -> 100));
        assertEquals(1, repository.dueJobs(event.createdAt(), 10).size());
        assertEquals(new NotificationJobProcessor.Batch(0, 0, 1), processor.process(() -> 60000));
        assertTrue(repository.dueJobs(event.createdAt().plusSeconds(3600), 10).isEmpty());
        var state = dynamoDbClient.getItem(r -> r.tableName("notification-test-sightings")
                .key(Map.of("pk", AttributeValue.builder().s("NOTIF#EVENT#failed-event").build(),
                        "sk", AttributeValue.builder().s("STATE").build()))).item();
        assertEquals("FAILED", state.get("jobStatus").s());
        assertEquals("INVALID_PAYLOAD", state.get("lastError").s());
        assertEquals(1, repository.preferences(alice.id()).unreadCount());
    }

    @Test
    void creationReplayAndExecutorUseExistingTableAndIsolateTimeline() throws Exception {
        repository.subscribe(alice.id(), start);
        var created = http("POST", "/api/species", alice,
                "{\"name\":\"Synthetic\",\"scientificName\":\"Avis test\",\"description\":\"test\",\"tips\":\"test\",\"imageUrl\":\"https://example.test/image.jpg\"}");
        assertEquals(201, created.statusCode(), created.body());
        String speciesId = new ObjectMapper().readTree(created.body()).path("id").asString();
        String body = "{\"speciesIds\":[\"" + speciesId + "\"],\"date\":\"2026-01-01\",\"time\":\"10:00:00\",\"imageUrl\":\"https://example.test/image.jpg\"}";
        var saved = http("POST", "/api/sightings", alice, body, "synthetic-outbox-idempotency");
        assertEquals(201, saved.statusCode(), saved.body());
        assertEquals(201, http("POST", "/api/sightings", alice, body, "synthetic-outbox-idempotency").statusCode());
        assertEquals(1, repository.dueJobs(Instant.now().plusSeconds(2), 10).size());
        assertEquals(409, http("POST", "/api/sightings", alice, body.replace("10:00:00", "11:00:00"),
                "synthetic-outbox-idempotency").statusCode());
        assertEquals(401, http("POST", "/api/internal/notification-jobs/process", null, null).statusCode());
        assertEquals(403, http("POST", "/api/internal/notification-jobs/process", alice, null).statusCode());
        UserRecord runner = new UserRecord("synthetic-runner", "Runner", "runner@example.test", "synthetic-hash", start, start);
        dynamoDbClient.putItem(r -> r.tableName("notification-test-users").item(Map.of(
                "pk", AttributeValue.builder().s("USER#synthetic-runner").build(),
                "itemType", AttributeValue.builder().s("USER").build(),
                "id", AttributeValue.builder().s(runner.id()).build(),
                "email", AttributeValue.builder().s(runner.email()).build(),
                "name", AttributeValue.builder().s(runner.name()).build(),
                "passwordHash", AttributeValue.builder().s("synthetic-hash").build(),
                "createdAt", AttributeValue.builder().s(start.toString()).build(),
                "updatedAt", AttributeValue.builder().s(start.toString()).build())));
        assertEquals(400, http("POST", "/api/internal/notification-jobs/process", runner, "{\"userId\":\"other\"}").statusCode());
        var result = http("POST", "/api/internal/notification-jobs/process", runner, null);
        assertEquals(200, result.statusCode(), result.body());
        assertEquals(1, new ObjectMapper().readTree(result.body()).path("completed").asInt());
        assertTrue(repository.dueJobs(Instant.now().plusSeconds(2), 10).isEmpty());
        var timeline = http("GET", "/api/sightings?from=2026-01-01&to=2026-01-01", alice, null);
        assertEquals(200, timeline.statusCode(), timeline.body());
        assertEquals(1, new ObjectMapper().readTree(timeline.body()).path("items").size());
        assertEquals(1, repository.preferences(alice.id()).unreadCount());
    }

    @Test
    void realHttpRoutesRequireJwtScopeInboxValidateCursorAndReadIdempotently() throws Exception {
        assertEquals(401, http("GET", "/api/notifications", null, null).statusCode());
        var subscribe = http("POST", "/api/notification-subscriptions", alice, null);
        assertEquals(200, subscribe.statusCode());
        repository.subscribe(bob.id(), start);
        var event = new Event("sighting-http", Instant.now().plusSeconds(1));
        repository.createNotice(alice.id(), event);
        assertEquals(404, http("PATCH", "/api/notifications/sighting-http/read", bob, null).statusCode());
        assertEquals(400, http("GET", "/api/notifications?limit=51", alice, null).statusCode());
        assertEquals(400, http("GET", "/api/notifications?cursor=invalid", alice, null).statusCode());
        assertEquals(200, http("PATCH", "/api/notifications/sighting-http/read", alice, null).statusCode());
        assertEquals(200, http("PATCH", "/api/notifications/sighting-http/read", alice, null).statusCode());
        var count = http("GET", "/api/notifications/unread-count", alice, null);
        assertEquals(200, count.statusCode());
        assertEquals(0, new ObjectMapper().readTree(count.body()).path("unreadCount").asLong());
        assertEquals(400, http("PUT", "/api/notification-preferences", alice, "{}").statusCode());
        assertEquals(400, http("PUT", "/api/notification-devices/install-1", alice, "{\"token\":\"bad\"}").statusCode());
        assertEquals(200, http("PUT", "/api/notification-preferences", alice, "{\"pushEnabled\":false}").statusCode());
        assertFalse(repository.preferences(alice.id()).pushEnabled());
    }

    private HttpResponse<String> http(String method, String path, UserRecord user, String body) throws Exception {
        return http(method, path, user, body, null);
    }

    private HttpResponse<String> http(String method, String path, UserRecord user, String body, String idempotency) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (user != null) builder.header("Authorization", "Bearer " + jwt.generateToken(user.id(), user.email()));
        if (idempotency != null) builder.header("Idempotency-Key", idempotency);
        return HttpClient.newHttpClient().send(builder.method(method,
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void enqueue(Event event) {
        var table = enhancedClient.table("notification-test-sightings",
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(NotificationJobItem.class));
        table.putItem(NotificationJobItem.create(event.sightingId(), event.createdAt(), true));
        table.putItem(NotificationJobItem.create(event.sightingId(), event.createdAt(), false));
    }

    @Test
    void activityUpdatesPreserveAccountAndOptimisticVersionAndCannotRecreateDeletedUser() {
        var before = userTable("notification-test-users").getItem(r -> r.key(k -> k.partitionValue("USER#" + alice.id())));
        repository.subscribe(alice.id(), start);
        repository.createNotice(alice.id(), new Event("metadata-event", start.plusSeconds(1)));
        var after = userTable("notification-test-users").getItem(r -> r.key(k -> k.partitionValue("USER#" + alice.id())));
        assertTrue(after.getVersion() > before.getVersion());
        assertNotNull(after.getNotificationActivityAt());
        assertEquals(alice.passwordHash(), after.getPasswordHash());
        assertEquals(alice.email(), after.getEmail());
        users.update(alice.id(), "Alice Updated", alice.email());
        assertNotNull(userTable("notification-test-users")
                .getItem(r -> r.key(k -> k.partitionValue("USER#" + alice.id()))).getNotificationActivityAt());
        users.delete(alice.id());
        assertThrows(tcc.aves.exception.ResourceNotFoundException.class,
                () -> repository.registerDevice(alice.id(), "install-a", "synthetic-token-1234567890"));
        assertTrue(getItem("notification-test-users", "USER#" + alice.id()).isEmpty());
        assertFalse(repository.createNotice(alice.id(), new Event("deleted-account-event", start.plusSeconds(2))));
    }

    @Test
    void staleQueueSnapshotDuringTerminalDeferralDoesNotLoseRescheduledJob() {
        repository.subscribe(alice.id(), start);
        repository.registerDevice(alice.id(), "install-a", "synthetic-token-1234567890");
        Event event = new Event("stale-queue", start.plusSeconds(1));
        enqueue(event);
        var stale = repository.dueJobs(event.createdAt(), 10).getFirst();
        repository.deferJob(stale, event.createdAt().plusSeconds(30), "NETWORK", false);
        var snapshot = new NotificationRepositoryDynamoDbAdapter(dynamoDbClient, enhancedClient,
                "notification-test-sightings", "notification-test-users") {
            @Override public List<Job> dueJobs(Instant now, int limit) { return List.of(stale); }
        };
        FcmGateway invalid = (e, d) -> new FcmGateway.Result(FcmGateway.Outcome.INVALID_PAYLOAD, null, "INVALID_PAYLOAD", Duration.ZERO);
        assertEquals(new NotificationJobProcessor.Batch(0, 1, 0),
                new NotificationJobProcessor(snapshot, invalid, Clock.fixed(event.createdAt(), ZoneOffset.UTC))
                        .process(() -> 60000));
        assertEquals(1, repository.dueJobs(event.createdAt().plusSeconds(30), 10).size());
        assertEquals(new NotificationJobProcessor.Batch(0, 0, 1),
                new NotificationJobProcessor(repository, invalid,
                        Clock.fixed(event.createdAt().plusSeconds(30), ZoneOffset.UTC)).process(() -> 60000));
        assertTrue(repository.dueJobs(event.createdAt().plusSeconds(60), 10).isEmpty());
    }

    @Test
    void parallelExecutorsAndResponseLostBeforeQueueRemovalDoNotDuplicateHistory() throws Exception {
        repository.subscribe(alice.id(), start);
        Event event = new Event("parallel-job", start.plusSeconds(1));
        enqueue(event);
        FcmGateway noDevices = (e, d) -> { throw new AssertionError("No device registered"); };
        var clock = Clock.fixed(event.createdAt(), ZoneOffset.UTC);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var batches = workers.invokeAll(List.<java.util.concurrent.Callable<NotificationJobProcessor.Batch>>of(
                    () -> new NotificationJobProcessor(repository, noDevices, clock).process(() -> 60000),
                    () -> new NotificationJobProcessor(repository, noDevices, clock).process(() -> 60000)));
            assertEquals(1, batches.get(0).get().completed() + batches.get(1).get().completed());
        }
        assertEquals(1, repository.preferences(alice.id()).unreadCount());
        assertTrue(repository.dueJobs(event.createdAt(), 10).isEmpty());
        Event lost = new Event("response-lost-job", start.plusSeconds(2));
        enqueue(lost);
        new NotificationDispatcher(repository, noDevices, clock).dispatch(lost, () -> 60000);
        assertEquals(1, repository.dueJobs(lost.createdAt(), 10).size());
        assertEquals(new NotificationJobProcessor.Batch(1, 0, 0),
                new NotificationJobProcessor(repository, noDevices,
                        Clock.fixed(lost.createdAt(), ZoneOffset.UTC)).process(() -> 60000));
        assertEquals(2, repository.preferences(alice.id()).unreadCount());
    }

    @Test
    void concurrentIdempotentCreationRollsBackLosingTransactionAndEditsDoNotEnqueue() throws Exception {
        var created = http("POST", "/api/species", alice,
                "{\"name\":\"Synthetic\",\"scientificName\":\"Avis concurrent\",\"description\":\"test\",\"tips\":\"test\",\"imageUrl\":\"https://example.test/image.jpg\"}");
        String speciesId = new ObjectMapper().readTree(created.body()).path("id").asString();
        String body = "{\"speciesIds\":[\"" + speciesId + "\"],\"date\":\"2026-01-01\",\"time\":\"10:00:00\",\"imageUrl\":\"https://example.test/image.jpg\"}";
        try (var workers = Executors.newFixedThreadPool(2)) {
            var results = workers.invokeAll(List.<java.util.concurrent.Callable<HttpResponse<String>>>of(
                    () -> http("POST", "/api/sightings", alice, body, "concurrent-outbox"),
                    () -> http("POST", "/api/sightings", alice, body, "concurrent-outbox")));
            var first = results.getFirst().get();
            var second = results.get(1).get();
            assertEquals(201, first.statusCode(), first.body());
            assertEquals(201, second.statusCode(), second.body());
            String id = new ObjectMapper().readTree(first.body()).path("id").asString();
            assertEquals(id, new ObjectMapper().readTree(second.body()).path("id").asString());
            assertEquals(1, repository.dueJobs(Instant.now().plusSeconds(2), 10).size());
            assertEquals("1", getItem("notification-test-species", "SPECIES#" + speciesId).get("referenceCount").n());
            assertEquals(200, http("PUT", "/api/sightings/" + id, alice, body.replace("10:00:00", "11:00:00")).statusCode());
            assertEquals(1, repository.dueJobs(Instant.now().plusSeconds(2), 10).size());
            assertEquals(204, http("DELETE", "/api/sightings/" + id, alice, null).statusCode());
            assertEquals(1, repository.dueJobs(Instant.now().plusSeconds(2), 10).size());
        }
    }
}
