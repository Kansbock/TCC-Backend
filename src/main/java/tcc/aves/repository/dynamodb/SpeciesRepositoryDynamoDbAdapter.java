package tcc.aves.repository.dynamodb;

import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactDeleteItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import tcc.aves.exception.ConcurrentUpdateException;
import tcc.aves.exception.DuplicateResourceException;
import tcc.aves.exception.ResourceInUseException;
import tcc.aves.exception.ResourceNotFoundException;
import tcc.aves.repository.SpeciesRecord;
import tcc.aves.repository.SpeciesRepository;
import tcc.aves.repository.dynamodb.model.ScientificNameReservationItem;
import tcc.aves.repository.dynamodb.model.SpeciesItem;
import tcc.aves.repository.dynamodb.support.DynamoDbSupport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementação DynamoDB do port {@link SpeciesRepository}.
 *
 * <p>Perfil e reserva de nome científico vivem na mesma tabela
 * ({@code aves-species}) e são sempre alterados juntos em transação, pelo
 * mesmo motivo do adapter de usuários. Listagem/busca usam {@code Scan}
 * deliberadamente — aceitável apenas porque o catálogo de espécies é
 * pequeno (ver decisão registrada no plano de migração).
 */
@Repository
public class SpeciesRepositoryDynamoDbAdapter implements SpeciesRepository {

    private static final Expression NOT_EXISTS = Expression.builder()
            .expression("attribute_not_exists(pk)")
            .build();

    private static final Expression NO_REFERENCES = Expression.builder()
            .expression("referenceCount = :zero")
            .putExpressionValue(":zero", AttributeValue.builder().n("0").build())
            .build();

    private final DynamoDbEnhancedClient enhancedClient;
    private final DynamoDbTable<SpeciesItem> speciesTable;
    private final DynamoDbTable<ScientificNameReservationItem> reservationTable;

    public SpeciesRepositoryDynamoDbAdapter(
            DynamoDbEnhancedClient enhancedClient,
            DynamoDbTable<SpeciesItem> speciesTable,
            DynamoDbTable<ScientificNameReservationItem> reservationTable) {
        this.enhancedClient = enhancedClient;
        this.speciesTable = speciesTable;
        this.reservationTable = reservationTable;
    }

    @Override
    public SpeciesRecord create(String name, String scientificName, String description, String tips, String imageUrl) {
        String id = UUID.randomUUID().toString();
        String normalized = normalize(scientificName);
        Instant now = Instant.now();

        SpeciesItem species = new SpeciesItem();
        species.setPk(SpeciesItem.keyFor(id));
        species.setId(id);
        species.setCommonName(name);
        species.setScientificName(scientificName);
        species.setScientificNameNormalized(normalized);
        species.setDescription(description);
        species.setTips(tips);
        species.setImageUrl(imageUrl);
        species.setReferenceCount(0L);
        species.setCreatedAt(now);
        species.setUpdatedAt(now);

        ScientificNameReservationItem reservation = new ScientificNameReservationItem();
        reservation.setPk(ScientificNameReservationItem.keyFor(normalized));
        reservation.setSpeciesId(id);

        try {
            enhancedClient.transactWriteItems(TransactWriteItemsEnhancedRequest.builder()
                    .addPutItem(reservationTable, TransactPutItemEnhancedRequest
                            .builder(ScientificNameReservationItem.class)
                            .item(reservation)
                            .conditionExpression(NOT_EXISTS)
                            .build())
                    .addPutItem(speciesTable, TransactPutItemEnhancedRequest
                            .builder(SpeciesItem.class)
                            .item(species)
                            .conditionExpression(NOT_EXISTS)
                            .build())
                    .build());
        } catch (TransactionCanceledException e) {
            if (DynamoDbSupport.isConditionFailure(e.cancellationReasons(), 0)) {
                throw new DuplicateResourceException("Nome científico já cadastrado");
            }
            RuntimeException translated = DynamoDbSupport.translateGeneric(e);
            throw translated != null ? translated : e;
        } catch (RuntimeException e) {
            RuntimeException translated = DynamoDbSupport.translateGeneric(e);
            throw translated != null ? translated : e;
        }

        return toRecord(species);
    }

    @Override
    public List<SpeciesRecord> findAll() {
        return scanAllSpecies().stream().map(this::toRecord).toList();
    }

    @Override
    public Optional<SpeciesRecord> findById(String id) {
        SpeciesItem item = speciesTable.getItem(GetItemEnhancedRequest.builder()
                .key(Key.builder().partitionValue(SpeciesItem.keyFor(id)).build())
                .consistentRead(true)
                .build());
        return Optional.ofNullable(item).map(this::toRecord);
    }

    @Override
    public List<SpeciesRecord> search(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return findAll();
        }
        return scanAllSpecies().stream()
                .filter(item -> containsIgnoreCase(item.getCommonName(), needle)
                        || containsIgnoreCase(item.getScientificName(), needle))
                .map(this::toRecord)
                .toList();
    }

    @Override
    public Map<String, SpeciesRecord> findAllByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<Key> keys = ids.stream().distinct()
                .map(id -> Key.builder().partitionValue(SpeciesItem.keyFor(id)).build())
                .toList();

        ReadBatch.Builder<SpeciesItem> batchBuilder = ReadBatch.builder(SpeciesItem.class)
                .mappedTableResource(speciesTable);
        keys.forEach(batchBuilder::addGetItem);

        Map<String, SpeciesRecord> result = new LinkedHashMap<>();
        enhancedClient.batchGetItem(BatchGetItemEnhancedRequest.builder()
                        .readBatches(batchBuilder.build())
                        .build())
                .resultsForTable(speciesTable)
                .forEach(item -> result.put(item.getId(), toRecord(item)));
        return result;
    }

    @Override
    public SpeciesRecord update(String id, String name, String scientificName, String description, String tips, String imageUrl) {
        SpeciesItem current = speciesTable.getItem(GetItemEnhancedRequest.builder()
                .key(Key.builder().partitionValue(SpeciesItem.keyFor(id)).build())
                .consistentRead(true)
                .build());
        if (current == null) {
            throw new ResourceNotFoundException("Espécie não encontrada");
        }

        String oldNormalized = current.getScientificNameNormalized();
        String newNormalized = normalize(scientificName);
        boolean nameChanged = !newNormalized.equals(oldNormalized);

        current.setCommonName(name);
        current.setScientificName(scientificName);
        current.setScientificNameNormalized(newNormalized);
        current.setDescription(description);
        current.setTips(tips);
        current.setImageUrl(imageUrl);
        current.setUpdatedAt(Instant.now());

        if (!nameChanged) {
            try {
                speciesTable.updateItem(current);
            } catch (RuntimeException e) {
                RuntimeException translated = DynamoDbSupport.translateGeneric(e);
                throw translated != null ? translated : e;
            }
            return toRecord(current);
        }

        ScientificNameReservationItem newReservation = new ScientificNameReservationItem();
        newReservation.setPk(ScientificNameReservationItem.keyFor(newNormalized));
        newReservation.setSpeciesId(id);

        Key oldReservationKey = Key.builder()
                .partitionValue(ScientificNameReservationItem.keyFor(oldNormalized))
                .build();

        try {
            enhancedClient.transactWriteItems(TransactWriteItemsEnhancedRequest.builder()
                    .addPutItem(reservationTable, TransactPutItemEnhancedRequest
                            .builder(ScientificNameReservationItem.class)
                            .item(newReservation)
                            .conditionExpression(NOT_EXISTS)
                            .build())
                    .addDeleteItem(reservationTable, oldReservationKey)
                    .addUpdateItem(speciesTable, current)
                    .build());
        } catch (TransactionCanceledException e) {
            if (DynamoDbSupport.isConditionFailure(e.cancellationReasons(), 0)) {
                throw new DuplicateResourceException("Nome científico já cadastrado por outra espécie");
            }
            if (DynamoDbSupport.isConditionFailure(e.cancellationReasons(), 2)) {
                throw new ConcurrentUpdateException(
                        "O registro foi alterado por outra operação. Tente novamente.");
            }
            RuntimeException translated = DynamoDbSupport.translateGeneric(e);
            throw translated != null ? translated : e;
        } catch (RuntimeException e) {
            RuntimeException translated = DynamoDbSupport.translateGeneric(e);
            throw translated != null ? translated : e;
        }

        return toRecord(current);
    }

    @Override
    public void delete(String id) {
        SpeciesItem current = speciesTable.getItem(GetItemEnhancedRequest.builder()
                .key(Key.builder().partitionValue(SpeciesItem.keyFor(id)).build())
                .consistentRead(true)
                .build());
        if (current == null) {
            throw new ResourceNotFoundException("Espécie não encontrada");
        }
        if (current.getReferenceCount() != null && current.getReferenceCount() > 0) {
            throw new ResourceInUseException(
                    "Espécie não pode ser removida: está referenciada por avistamentos existentes");
        }

        try {
            enhancedClient.transactWriteItems(TransactWriteItemsEnhancedRequest.builder()
                    .addDeleteItem(speciesTable, deleteConditionalOnNoReferences(id))
                    .addDeleteItem(reservationTable,
                            Key.builder().partitionValue(ScientificNameReservationItem.keyFor(
                                    current.getScientificNameNormalized())).build())
                    .build());
        } catch (TransactionCanceledException e) {
            if (DynamoDbSupport.isConditionFailure(e.cancellationReasons(), 0)) {
                throw new ResourceInUseException(
                        "Espécie não pode ser removida: está referenciada por avistamentos existentes");
            }
            RuntimeException translated = DynamoDbSupport.translateGeneric(e);
            throw translated != null ? translated : e;
        } catch (RuntimeException e) {
            RuntimeException translated = DynamoDbSupport.translateGeneric(e);
            throw translated != null ? translated : e;
        }
    }

    /**
     * Chave de exclusão condicionada a {@code referenceCount = 0} — fecha a
     * pequena janela de corrida entre a leitura acima e a exclusão, onde um
     * avistamento poderia passar a referenciar a espécie entretanto.
     */
    private TransactDeleteItemEnhancedRequest deleteConditionalOnNoReferences(String id) {
        return TransactDeleteItemEnhancedRequest.builder()
                .key(Key.builder().partitionValue(SpeciesItem.keyFor(id)).build())
                .conditionExpression(NO_REFERENCES)
                .build();
    }

    private List<SpeciesItem> scanAllSpecies() {
        Expression filter = Expression.builder()
                .expression("itemType = :type")
                .putExpressionValue(":type", AttributeValue.builder().s(SpeciesItem.ITEM_TYPE).build())
                .build();

        List<SpeciesItem> items = new ArrayList<>();
        speciesTable.scan(ScanEnhancedRequest.builder()
                        .filterExpression(filter)
                        .consistentRead(true)
                        .build())
                .items()
                .forEach(items::add);
        return items;
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String normalize(String scientificName) {
        return scientificName == null ? null : scientificName.trim().toLowerCase(Locale.ROOT);
    }

    private SpeciesRecord toRecord(SpeciesItem item) {
        return new SpeciesRecord(
                item.getId(),
                item.getCommonName(),
                item.getScientificName(),
                item.getDescription(),
                item.getTips(),
                item.getImageUrl(),
                item.getReferenceCount() == null ? 0L : item.getReferenceCount(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }
}
