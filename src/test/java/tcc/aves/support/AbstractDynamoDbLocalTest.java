package tcc.aves.support;

import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateGlobalSecondaryIndexAction;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import tcc.aves.repository.dynamodb.model.EmailReservationItem;
import tcc.aves.repository.dynamodb.model.SightingIdempotencyItem;
import tcc.aves.repository.dynamodb.model.SightingItem;
import tcc.aves.repository.dynamodb.model.SpeciesItem;
import tcc.aves.repository.dynamodb.model.SpeciesSightingRefItem;
import tcc.aves.repository.dynamodb.model.UserItem;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

@TestInstance(Lifecycle.PER_CLASS)
public abstract class AbstractDynamoDbLocalTest {

    protected static final String REGION = "us-east-1";

    @Container
    protected static final GenericContainer<?> DYNAMODB_LOCAL = new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:latest"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
            .withExposedPorts(8000);

    protected DynamoDbClient dynamoDbClient;
    protected DynamoDbEnhancedClient enhancedClient;

    protected void initializeClients() {
        dynamoDbClient = DynamoDbClient.builder()
                .endpointOverride(java.net.URI.create(endpointOverride()))
                .region(software.amazon.awssdk.regions.Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    }

    protected static String endpointOverride() {
        return "http://" + DYNAMODB_LOCAL.getHost() + ":" + DYNAMODB_LOCAL.getMappedPort(8000);
    }

    protected void recreateTables(String usersTableName, String speciesTableName, String sightingsTableName) {
        deleteTableIfExists(sightingsTableName);
        deleteTableIfExists(speciesTableName);
        deleteTableIfExists(usersTableName);
        createUsersTable(usersTableName);
        createSpeciesTable(speciesTableName);
        createSightingsTable(sightingsTableName);
    }

    protected DynamoDbTable<UserItem> userTable(String tableName) {
        return enhancedClient.table(tableName, TableSchema.fromBean(UserItem.class));
    }

    protected DynamoDbTable<EmailReservationItem> emailReservationTable(String tableName) {
        return enhancedClient.table(tableName, TableSchema.fromBean(EmailReservationItem.class));
    }

    protected DynamoDbTable<SpeciesItem> speciesTable(String tableName) {
        return enhancedClient.table(tableName, TableSchema.fromBean(SpeciesItem.class));
    }

    protected DynamoDbTable<tcc.aves.repository.dynamodb.model.ScientificNameReservationItem> scientificReservationTable(String tableName) {
        return enhancedClient.table(tableName, TableSchema.fromBean(tcc.aves.repository.dynamodb.model.ScientificNameReservationItem.class));
    }

    protected DynamoDbTable<SightingItem> sightingTable(String tableName) {
        return enhancedClient.table(tableName, TableSchema.fromBean(SightingItem.class));
    }

    protected DynamoDbTable<SpeciesSightingRefItem> speciesSightingRefTable(String tableName) {
        return enhancedClient.table(tableName, TableSchema.fromBean(SpeciesSightingRefItem.class));
    }

    protected DynamoDbIndex<SightingItem> sightingTimelineIndex(String tableName) {
        return sightingTable(tableName).index(SightingItem.TIMELINE_INDEX_NAME);
    }

    protected DynamoDbTable<SightingIdempotencyItem> sightingIdempotencyTable(String tableName) {
        return enhancedClient.table(tableName, TableSchema.fromBean(SightingIdempotencyItem.class));
    }

    protected Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> getItem(String tableName, String pk) {
        return dynamoDbClient.getItem(request -> request.tableName(tableName).key(Map.of(
                "pk", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s(pk).build()))).item();
    }

    protected Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> getItem(String tableName, String pk, String sk) {
        return dynamoDbClient.getItem(request -> request.tableName(tableName).key(Map.of(
                "pk", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s(pk).build(),
                "sk", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s(sk).build()))).item();
    }

    protected long countItems(String tableName) {
        return dynamoDbClient.scanPaginator(request -> request.tableName(tableName))
                .items()
                .stream()
                .count();
    }

    private void createUsersTable(String tableName) {
        dynamoDbClient.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .build());
        waitUntilExists(tableName);
    }

    private void createSpeciesTable(String tableName) {
        dynamoDbClient.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .build());
        waitUntilExists(tableName);
    }

    private void createSightingsTable(String tableName) {
        dynamoDbClient.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("timelinePk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("timelineSk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("primarySpeciesPk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("primarySpeciesSk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName(SightingItem.TIMELINE_INDEX_NAME)
                        .keySchema(
                                KeySchemaElement.builder().attributeName("timelinePk").keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName("timelineSk").keyType(KeyType.RANGE).build())
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build(),
                    GlobalSecondaryIndex.builder()
                        .indexName(SightingItem.PRIMARY_SPECIES_INDEX_NAME)
                        .keySchema(
                                KeySchemaElement.builder().attributeName("primarySpeciesPk").keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName("primarySpeciesSk").keyType(KeyType.RANGE).build())
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build())
                .build());
        waitUntilExists(tableName);
    }

    private void deleteTableIfExists(String tableName) {
        try {
            dynamoDbClient.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDbClient.waiter().waitUntilTableNotExists(DescribeTableRequest.builder().tableName(tableName).build());
        } catch (ResourceNotFoundException ignored) {
            // table absent already
        }
    }

    private void waitUntilExists(String tableName) {
        dynamoDbClient.waiter().waitUntilTableExists(DescribeTableRequest.builder().tableName(tableName).build());
    }
}
