package tcc.aves.notification;

import com.google.auth.oauth2.ServiceAccountCredentials;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tcc.aves.repository.NotificationRepository.Device;
import tcc.aves.repository.NotificationRepository.Event;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

public class FcmHttpGateway implements FcmGateway {
    @FunctionalInterface
    public interface TokenProvider { String token() throws IOException; }
    private final HttpClient client;
    private final TokenProvider tokens;
    private final URI endpoint;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public static FcmHttpGateway fromEnvironment(Map<String, String> environment) {
        String project = environment.get("FCM_PROJECT_ID");
        String source = environment.get("FCM_SERVICE_ACCOUNT_JSON");
        if (project == null || !project.matches("[a-z][a-z0-9-]{4,62}")
                || source == null || source.isBlank()) {
            throw new IllegalArgumentException("Configuração FCM obrigatória ausente ou inválida");
        }
        ServiceAccountCredentials service;
        try {
            service = ServiceAccountCredentials.fromStream(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)),
                    BoundedOAuthTransport::new);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Credencial FCM inválida; conteúdo omitido");
        }
        if (!project.equals(service.getProjectId())) throw new IllegalArgumentException("Projeto da credencial FCM não corresponde à configuração");
        if (!URI.create("https://oauth2.googleapis.com/token").equals(service.getTokenServerUri())) {
            throw new IllegalArgumentException("Endpoint OAuth da credencial FCM inválido");
        }
        TokenProvider provider = tokenProvider(service);
        return new FcmHttpGateway(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                provider, URI.create("https://fcm.googleapis.com/v1/projects/" + project + "/messages:send"), Clock.systemUTC());
    }

    static TokenProvider tokenProvider(ServiceAccountCredentials service) {
        var scoped = service.createWithCustomRetryStrategy(false)
                .createScoped(List.of("https://www.googleapis.com/auth/firebase.messaging"));
        return () -> {
            scoped.refreshIfExpired();
            return scoped.getAccessToken().getTokenValue();
        };
    }

    public FcmHttpGateway(HttpClient client, TokenProvider tokens, URI endpoint, Clock clock) {
        this.client = client;
        this.tokens = tokens;
        this.endpoint = endpoint;
        this.clock = clock;
    }

    @Override
    public Result send(Event event, Device device) {
        try {
            String body = json.writeValueAsString(Map.of("message", Map.of(
                    "token", device.token(), "notification", Map.of("title", event.title(), "body", event.body()),
                    "data", Map.of("sightingId", event.sightingId(), "notificationId", event.sightingId(), "userId", device.userId()),
                    "android", Map.of("priority", "HIGH", "notification", Map.of(
                            "channel_id", "sightings", "tag", "sighting-" + event.sightingId(), "sound", "default")))));
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + tokens.token()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            Duration delay = retryAfter(response.headers().firstValue("Retry-After").orElse(null));
            int status = response.statusCode();
            if (status == 429 || status >= 500) {
                if (status == 429 && delay.compareTo(Duration.ofSeconds(60)) < 0) delay = Duration.ofSeconds(60);
                return new Result(Outcome.RETRYABLE, null, "HTTP_" + status, delay);
            }
            JsonNode root;
            try {
                root = json.readTree(response.body());
            } catch (tools.jackson.core.JacksonException e) {
                return new Result(Outcome.CONFIGURATION, null, "INVALID_RESPONSE", Duration.ZERO);
            }
            if (status == 200 && root != null && root.path("name").isString() && !root.path("name").asString().isBlank()) {
                return new Result(Outcome.ACCEPTED, root.path("name").asString(), null, Duration.ZERO);
            }
            String fcmCode = "";
            boolean payloadInvalid = false;
            if (root != null) {
                for (JsonNode detail : root.path("error").path("details")) {
                    if ("type.googleapis.com/google.firebase.fcm.v1.FcmError".equals(detail.path("@type").asString())) {
                        fcmCode = detail.path("errorCode").asString();
                    }
                    if ("type.googleapis.com/google.rpc.BadRequest".equals(detail.path("@type").asString())) payloadInvalid = true;
                }
            }
            if (status == 404 && "UNREGISTERED".equals(fcmCode)) {
                return new Result(Outcome.UNREGISTERED, null, "UNREGISTERED", Duration.ZERO);
            }
            if (status == 400) {
                return new Result(payloadInvalid || !"INVALID_ARGUMENT".equals(fcmCode) ? Outcome.INVALID_PAYLOAD : Outcome.INVALID_TOKEN,
                        null, payloadInvalid ? "INVALID_PAYLOAD" : "INVALID_ARGUMENT", Duration.ZERO);
            }
            return new Result(Outcome.CONFIGURATION, null, "HTTP_" + status, Duration.ZERO);
        } catch (IOException e) {
            return new Result(Outcome.RETRYABLE, null, "NETWORK_OR_OAUTH", Duration.ofSeconds(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Envio FCM interrompido");
        }
    }

    private Duration retryAfter(String header) {
        if (header == null) return Duration.ZERO;
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(header)));
        } catch (NumberFormatException e) {
            try {
                Duration delay = Duration.between(clock.instant(), ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (DateTimeParseException invalid) {
                return Duration.ZERO;
            }
        }
    }
}
