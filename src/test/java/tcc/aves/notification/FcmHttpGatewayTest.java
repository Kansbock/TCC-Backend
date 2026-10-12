package tcc.aves.notification;

import com.sun.net.httpserver.HttpServer;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tcc.aves.repository.NotificationRepository.Device;
import tcc.aves.repository.NotificationRepository.Event;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FcmHttpGatewayTest {
    private HttpServer server;
    private FcmHttpGateway gateway;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> received = new AtomicReference<>();
    private int status = 200;
    private String response = "{\"name\":\"projects/synthetic/messages/message-1\"}";
    private String retryAfter;
    private final Event event = new Event("sighting-1", Instant.parse("2026-01-01T00:00:00Z"));
    private final Device device = new Device("user-1", "install-1", "synthetic-native-token", "generation-1");

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/send", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        gateway = new FcmHttpGateway(HttpClient.newHttpClient(), () -> "synthetic-oauth",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/send"),
                Clock.fixed(event.createdAt(), ZoneOffset.UTC));
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void sendsNativePayloadAndTreatsMessageIdAsAcceptance() {
        var result = gateway.send(event, device);
        assertEquals(FcmGateway.Outcome.ACCEPTED, result.outcome());
        JsonNode body = json.readTree(received.get());
        assertEquals(device.token(), body.path("message").path("token").asString());
        assertEquals(device.userId(), body.path("message").path("data").path("userId").asString());
        assertEquals(event.sightingId(), body.path("message").path("data").path("sightingId").asString());
        assertEquals("sightings", body.path("message").path("android").path("notification").path("channel_id").asString());
        assertFalse(body.has("GCM"));
    }

    @Test
    void handlesUnregisteredWithoutConfusingOrdinary404() {
        status = 404;
        response = "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"UNREGISTERED\"}]}}";
        assertEquals(FcmGateway.Outcome.UNREGISTERED, gateway.send(event, device).outcome());
        response = "{\"error\":{\"status\":\"NOT_FOUND\"}}";
        assertEquals(FcmGateway.Outcome.CONFIGURATION, gateway.send(event, device).outcome());
    }

    @Test
    void distinguishesPayloadTokenAndAuthorizationErrors() {
        status = 400;
        response = "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.BadRequest\"}]}}";
        assertEquals(FcmGateway.Outcome.INVALID_PAYLOAD, gateway.send(event, device).outcome());
        response = "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"INVALID_ARGUMENT\"}]}}";
        assertEquals(FcmGateway.Outcome.INVALID_TOKEN, gateway.send(event, device).outcome());
        for (int code : new int[]{401, 403}) {
            status = code;
            assertEquals(FcmGateway.Outcome.CONFIGURATION, gateway.send(event, device).outcome());
        }
    }

    @Test
    void respectsQuotaMinimumAndBothRetryAfterFormats() {
        status = 429;
        retryAfter = "1";
        assertEquals(Duration.ofSeconds(60), gateway.send(event, device).retryAfter());
        retryAfter = "120";
        assertEquals(Duration.ofSeconds(120), gateway.send(event, device).retryAfter());
        status = 503;
        retryAfter = "Thu, 1 Jan 2026 00:02:00 GMT";
        assertEquals(Duration.ofSeconds(120), gateway.send(event, device).retryAfter());
        assertEquals(FcmGateway.Outcome.RETRYABLE, gateway.send(event, device).outcome());
    }

    @Test
    void missingMalformedOrWrongCredentialCannotLeakItsContents() {
        assertThrows(IllegalArgumentException.class, () -> FcmHttpGateway.fromEnvironment(Map.of()));
        String sensitive = "synthetic-secret-value";
        var failure = assertThrows(IllegalArgumentException.class, () -> FcmHttpGateway.fromEnvironment(
                Map.of("FCM_PROJECT_ID", "synthetic-project", "FCM_SERVICE_ACCOUNT_JSON", sensitive)));
        assertFalse(failure.getMessage().contains(sensitive));
    }

    @Test
    void networkAndMalformedSuccessRemainExplicitFailures() {
        response = "not-json";
        assertEquals(FcmGateway.Outcome.CONFIGURATION, gateway.send(event, device).outcome());
        server.stop(0);
        assertEquals(FcmGateway.Outcome.RETRYABLE, gateway.send(event, device).outcome());
    }

    @Test
    void googleAuthObtainsAndRefreshesShortLivedOAuthOnlyAgainstLocalSyntheticServer() throws Exception {
        AtomicInteger refreshed = new AtomicInteger();
        server.createContext("/oauth", exchange -> {
            int count = refreshed.incrementAndGet();
            byte[] bytes = ("{\"access_token\":\"synthetic-oauth-" + count + "\",\"token_type\":\"Bearer\",\"expires_in\":60}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        ServiceAccountCredentials synthetic = ServiceAccountCredentials.newBuilder()
                .setClientEmail("synthetic@example.test").setClientId("synthetic-client")
                .setPrivateKey((RSAPrivateKey) generator.generateKeyPair().getPrivate())
                .setHttpTransportFactory(BoundedOAuthTransport::new)
                .setPrivateKeyId("synthetic-key").setProjectId("synthetic-project")
                .setTokenServerUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oauth")).build();
        var provider = FcmHttpGateway.tokenProvider(synthetic);
        assertEquals("synthetic-oauth-1", provider.token());
        assertEquals("synthetic-oauth-2", provider.token());
        assertEquals(2, refreshed.get());
    }

    @Test
    void oauthTransportBoundsSlowRefreshWithoutInternalRetries() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new AtomicInteger();
        server.createContext("/slow-oauth", exchange -> {
            calls.incrementAndGet();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var service = ServiceAccountCredentials.newBuilder().setClientEmail("synthetic@example.test")
                .setPrivateKey((RSAPrivateKey) generator.generateKeyPair().getPrivate())
                .setProjectId("synthetic-project").setHttpTransportFactory(BoundedOAuthTransport::new)
                .setTokenServerUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow-oauth")).build();
        long began = System.nanoTime();
        try {
            assertThrows(java.io.IOException.class, () -> FcmHttpGateway.tokenProvider(service).token());
            assertTrue(Duration.ofNanos(System.nanoTime() - began).compareTo(Duration.ofSeconds(5)) < 0);
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
        }
    }
}
