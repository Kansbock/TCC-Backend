package tcc.aves.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import tcc.aves.support.AbstractDynamoDbLocalTest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tcc.aves.security.JwtUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"jwt.secret=synthetic-disabled-executor-test-key-1234567890",
                "app.notifications.runner-user-id=synthetic-runner",
                "app.notifications.processing-enabled=false"})
@Testcontainers
class NotificationExecutorDisabledTest extends AbstractDynamoDbLocalTest {
    @LocalServerPort int port;
    @Autowired JwtUtil jwt;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DYNAMODB_LOCAL.start();
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        registry.add("aws.dynamodb.endpoint-override", AbstractDynamoDbLocalTest::endpointOverride);
        registry.add("app.dynamodb.users-table", () -> "notification-disabled-users");
        registry.add("app.dynamodb.species-table", () -> "notification-disabled-species");
        registry.add("app.dynamodb.sightings-table", () -> "notification-disabled-sightings");
    }

    @BeforeAll
    void setup() {
        initializeClients();
        recreateTables("notification-disabled-users", "notification-disabled-species", "notification-disabled-sightings");
        dynamoDbClient.putItem(r -> r.tableName("notification-disabled-users").item(Map.of(
                "pk", AttributeValue.builder().s("USER#synthetic-runner").build(),
                "itemType", AttributeValue.builder().s("USER").build(),
                "id", AttributeValue.builder().s("synthetic-runner").build(),
                "email", AttributeValue.builder().s("runner@example.test").build(),
                "name", AttributeValue.builder().s("Runner").build(),
                "passwordHash", AttributeValue.builder().s("synthetic-hash").build(),
                "createdAt", AttributeValue.builder().s(Instant.EPOCH.toString()).build(),
                "updatedAt", AttributeValue.builder().s(Instant.EPOCH.toString()).build())));
    }

    @AfterAll
    void cleanup() { dynamoDbClient.close(); }

    @Test
    void disabledExecutorReturnsExplicit503WithoutInitializingFcm() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/internal/notification-jobs/process"))
                .header("Authorization", "Bearer " + jwt.generateToken("synthetic-runner", "runner@example.test"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(503, response.statusCode(), response.body());
    }
}
