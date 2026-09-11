package tcc.aves.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import tcc.aves.repository.dynamodb.model.EmailReservationItem;
import tcc.aves.repository.dynamodb.model.ScientificNameReservationItem;
import tcc.aves.repository.dynamodb.model.SightingItem;
import tcc.aves.repository.dynamodb.model.SpeciesItem;
import tcc.aves.repository.dynamodb.model.SpeciesSightingRefItem;
import tcc.aves.repository.dynamodb.model.UserItem;

/**
 * Expõe handles tipados ({@code DynamoDbTable}/{@code DynamoDbIndex}) para
 * cada combinação tabela + schema de item usada pelos adapters de
 * repositório. As tabelas físicas não são criadas por aqui — apenas
 * referenciadas pelo nome configurado em {@link DynamoDbProperties}.
 */
@Configuration
public class DynamoDbTableConfig {

    @Bean
    public DynamoDbTable<UserItem> userTable(DynamoDbEnhancedClient client, DynamoDbProperties properties) {
        return client.table(properties.usersTable(), TableSchema.fromBean(UserItem.class));
    }

    @Bean
    public DynamoDbTable<EmailReservationItem> emailReservationTable(
            DynamoDbEnhancedClient client, DynamoDbProperties properties) {
        return client.table(properties.usersTable(), TableSchema.fromBean(EmailReservationItem.class));
    }

    @Bean
    public DynamoDbTable<SpeciesItem> speciesTable(DynamoDbEnhancedClient client, DynamoDbProperties properties) {
        return client.table(properties.speciesTable(), TableSchema.fromBean(SpeciesItem.class));
    }

    @Bean
    public DynamoDbTable<ScientificNameReservationItem> scientificNameReservationTable(
            DynamoDbEnhancedClient client, DynamoDbProperties properties) {
        return client.table(properties.speciesTable(), TableSchema.fromBean(ScientificNameReservationItem.class));
    }

    @Bean
    public DynamoDbTable<SightingItem> sightingTable(DynamoDbEnhancedClient client, DynamoDbProperties properties) {
        return client.table(properties.sightingsTable(), TableSchema.fromBean(SightingItem.class));
    }

    @Bean
    public DynamoDbTable<SpeciesSightingRefItem> speciesSightingRefTable(
            DynamoDbEnhancedClient client, DynamoDbProperties properties) {
        return client.table(properties.sightingsTable(), TableSchema.fromBean(SpeciesSightingRefItem.class));
    }

    @Bean
    public DynamoDbIndex<SightingItem> sightingTimelineIndex(DynamoDbTable<SightingItem> sightingTable) {
        return sightingTable.index(SightingItem.TIMELINE_INDEX_NAME);
    }
}
