package tcc.aves.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import java.net.URI;
import java.time.Duration;

/**
 * Clientes AWS SDK v2 para o DynamoDB.
 *
 * <p>Credenciais nunca são lidas de configuração da aplicação: o
 * {@code DynamoDbClient} usa a cadeia padrão de credenciais da AWS
 * (variáveis de ambiente, {@code ~/.aws/credentials}, role da instância/tarefa
 * etc.), conforme recomendado pela documentação oficial do SDK.
 */
@Configuration
@EnableConfigurationProperties({DynamoDbProperties.class, SightingProperties.class})
public class DynamoDbConfig {

    @Bean
    public DynamoDbClient dynamoDbClient(
            @Value("${aws.region}") String region,
            @Value("${aws.dynamodb.endpoint-override:}") String endpointOverride) {

        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(region))
                .overrideConfiguration(b -> b
                        .apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(5)));

        URI override = DynamoDbProperties.parseEndpointOverride(endpointOverride);
        if (override != null) {
            builder.endpointOverride(override);
        }

        return builder.build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }
}
