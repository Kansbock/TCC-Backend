package tcc.aves.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/**
 * Propriedades de conexão e nomes de tabela do DynamoDB.
 *
 * <p>As tabelas e índices descritos aqui <b>não</b> são criados pela
 * aplicação — devem existir previamente (ver README, seção "Configuração
 * manual da AWS"). {@code endpointOverride} só deve ser usado em
 * desenvolvimento/teste, apontando para o DynamoDB Local.
 */
@ConfigurationProperties(prefix = "app.dynamodb")
public record DynamoDbProperties(
        String usersTable,
        String speciesTable,
        String sightingsTable,
        String sightingsTimelineIndex
) {

    public static URI parseEndpointOverride(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return URI.create(raw);
    }
}
