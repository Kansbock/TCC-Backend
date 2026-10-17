package tcc.aves.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.regions.Region;
import tcc.aves.repository.NotificationRepository;
import tcc.aves.repository.dynamodb.NotificationRepositoryDynamoDbAdapter;
import tcc.aves.notification.FcmGateway;
import tcc.aves.notification.FcmHttpGateway;
import java.time.Duration;

@Configuration
public class NotificationConfig {
    @Bean
    public DynamoDbClient notificationDynamoDbClient(@Value("${aws.region}") String region,
            @Value("${aws.dynamodb.endpoint-override:}") String endpoint) {
        var builder = DynamoDbClient.builder().region(Region.of(region))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(2))
                        .apiCallAttemptTimeout(Duration.ofSeconds(1)));
        var override = DynamoDbProperties.parseEndpointOverride(endpoint);
        if (override != null) builder.endpointOverride(override);
        return builder.build();
    }

    @Bean
    @Lazy
    public FcmGateway fcmGateway() {
        return FcmHttpGateway.fromEnvironment(System.getenv());
    }

    @Bean
    public NotificationRepository notificationRepository(
            @Qualifier("notificationDynamoDbClient") DynamoDbClient client,
            @Value("${app.dynamodb.sightings-table}") String table,
            @Value("${app.dynamodb.users-table}") String usersTable) {
        return new NotificationRepositoryDynamoDbAdapter(client,
                DynamoDbEnhancedClient.builder().dynamoDbClient(client).build(), table, usersTable);
    }
}
