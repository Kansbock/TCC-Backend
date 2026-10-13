package tcc.aves.repository.dynamodb;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import tcc.aves.config.SightingProperties;
import tcc.aves.exception.ConcurrentUpdateException;
import tcc.aves.exception.DuplicateResourceException;
import tcc.aves.exception.ResourceNotFoundException;
import tcc.aves.repository.SightingPage;
import tcc.aves.repository.SightingQuery;
import tcc.aves.repository.SightingRecord;
import tcc.aves.repository.SightingRepository;
import tcc.aves.repository.dynamodb.model.SightingIdempotencyItem;
import tcc.aves.repository.dynamodb.model.NotificationJobItem;
import tcc.aves.repository.dynamodb.model.SightingItem;
import tcc.aves.repository.dynamodb.model.SpeciesItem;
import tcc.aves.repository.dynamodb.model.SpeciesSightingRefItem;
import tcc.aves.repository.dynamodb.support.DynamoDbSupport;
import tcc.aves.repository.dynamodb.support.SightingCursorCodec;
import tcc.aves.repository.dynamodb.support.TimeKeys;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;

/**
 * Implementação DynamoDB do port {@link SightingRepository}.
 *
 * <p>Cada avistamento grava três tipos de item na tabela {@code aves-sightings}:
 * o item de detalhes ({@link SightingItem}, também indexado pela GSI de
 * timeline) e um {@link SpeciesSightingRefItem} por espécie referenciada.
 * Toda escrita (create/update/delete) também ajusta {@code referenceCount}
 * na tabela {@code aves-species} na mesma transação — nunca em uma chamada
 * separada — para que contadores e referências nunca fiquem inconsistentes,
 * mesmo em caso de falha parcial.
 *
 * <p>Ao atualizar um avistamento, todos os itens de referência antigos são
 * removidos e recriados (em vez de só ajustar a diferença), porque a chave
 * de ordenação do item de referência embute o {@code observedAt} — se só a
 * diferença de espécies fosse ajustada, referências de espécies que
 * permanecem no avistamento ficariam com uma chave de ordenação
 * desatualizada sempre que a data/hora do avistamento mudasse.
 */
@Repository
public class SightingRepositoryDynamoDbAdapter implements SightingRepository {

    private final DynamoDbEnhancedClient enhancedClient;
    private final DynamoDbTable<SightingItem> sightingTable;
    private final DynamoDbTable<SpeciesSightingRefItem> refTable;
    private final DynamoDbIndex<SightingItem> timelineIndex;
    private final DynamoDbIndex<SightingItem> primarySpeciesIndex;
    private final DynamoDbTable<SpeciesItem> speciesTable;
    private final DynamoDbTable<SightingIdempotencyItem> idempotencyTable;
    private final DynamoDbTable<NotificationJobItem> notificationJobs;
    private final SightingProperties properties;
    private final SightingCursorCodec cursorCodec;

    @Autowired
    public SightingRepositoryDynamoDbAdapter(
            DynamoDbEnhancedClient enhancedClient,
            DynamoDbTable<SightingItem> sightingTable,
            DynamoDbTable<SpeciesSightingRefItem> refTable,
            @Qualifier("sightingTimelineIndex") DynamoDbIndex<SightingItem> timelineIndex,
            @Qualifier("sightingPrimarySpeciesIndex") DynamoDbIndex<SightingItem> primarySpeciesIndex,
            DynamoDbTable<SpeciesItem> speciesTable,
            DynamoDbTable<SightingIdempotencyItem> idempotencyTable,
            SightingProperties properties,
            SightingCursorCodec cursorCodec) {
        this.enhancedClient = enhancedClient;
        this.sightingTable = sightingTable;
        this.refTable = refTable;
        this.timelineIndex = timelineIndex;
        this.primarySpeciesIndex = primarySpeciesIndex;
        this.speciesTable = speciesTable;
        this.idempotencyTable = idempotencyTable;
        this.notificationJobs = enhancedClient.table(sightingTable.tableName(), TableSchema.fromBean(NotificationJobItem.class));
        this.properties = properties;
        this.cursorCodec = cursorCodec;
    }

    public SightingRepositoryDynamoDbAdapter(
            DynamoDbEnhancedClient enhancedClient,
            DynamoDbTable<SightingItem> sightingTable,
            DynamoDbTable<SpeciesSightingRefItem> refTable,
            DynamoDbIndex<SightingItem> timelineIndex,
            DynamoDbTable<SpeciesItem> speciesTable,
            DynamoDbTable<SightingIdempotencyItem> idempotencyTable,
            SightingProperties properties,
            SightingCursorCodec cursorCodec) {
        this(enhancedClient, sightingTable, refTable, timelineIndex,
                sightingTable.index(SightingItem.PRIMARY_SPECIES_INDEX_NAME),
                speciesTable, idempotencyTable, properties, cursorCodec);
    }

    @Override
    public SightingRecord create(List<String> speciesIds, LocalDate date, LocalTime time, String imageUrl) {
        Set<String> distinctIds = new LinkedHashSet<>(speciesIds);
        Map<String, SpeciesItem> species = fetchSpeciesOrThrow(distinctIds);

        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant observedAt = toInstant(date, time);

        SightingItem sighting = new SightingItem();
        sighting.setPk(SightingItem.keyFor(id));
        sighting.setSk(SightingItem.DETAILS_SORT_KEY);
        sighting.setId(id);
        sighting.setDate(date);
        sighting.setTime(time);
        sighting.setImageUrl(imageUrl);
        sighting.setSpeciesIds(List.copyOf(distinctIds));
        sighting.setObservedAt(observedAt);
        sighting.setTimelinePk(SightingItem.TIMELINE_PK_PREFIX + YearMonth.from(date));
        sighting.setTimelineSk(TimeKeys.sortable(observedAt) + "#" + id);
        sighting.setPrimarySpeciesPk(SightingItem.PRIMARY_SPECIES_PK);
        sighting.setPrimarySpeciesSk(primarySpeciesSortKey(species.get(speciesIds.get(0)).getCommonName(), observedAt, id));
        sighting.setCreatedAt(now);
        sighting.setUpdatedAt(now);

        TransactWriteItemsEnhancedRequest.Builder tx = TransactWriteItemsEnhancedRequest.builder()
                .addPutItem(sightingTable, TransactPutItemEnhancedRequest.builder(SightingItem.class)
                        .item(sighting)
                        .conditionExpression(notExists())
                        .build());

        for (String speciesId : distinctIds) {
            tx.addPutItem(refTable, newRefItem(speciesId, observedAt, id));
            SpeciesItem current = species.get(speciesId);
            current.setReferenceCount(current.getReferenceCount() + 1);
            current.setUpdatedAt(now);
            tx.addUpdateItem(speciesTable, current);
        }

        addNotificationJob(tx, id, now, distinctIds.size());
        executeTransaction(tx.build());

        return toRecord(sighting);
    }

    @Override
    public SightingRecord createIdempotent(String idempotencyKey, List<String> speciesIds, LocalDate date, LocalTime time, String imageUrl) {
        String fingerprint = computeFingerprint(speciesIds, date, time, imageUrl);

        SightingIdempotencyItem existing = getIdempotencyItem(idempotencyKey);
        if (existing != null) {
            return replayOrConflict(existing, fingerprint);
        }

        Set<String> distinctIds = new LinkedHashSet<>(speciesIds);
        Map<String, SpeciesItem> species = fetchSpeciesOrThrow(distinctIds);

        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant observedAt = toInstant(date, time);

        SightingItem sighting = new SightingItem();
        sighting.setPk(SightingItem.keyFor(id));
        sighting.setSk(SightingItem.DETAILS_SORT_KEY);
        sighting.setId(id);
        sighting.setDate(date);
        sighting.setTime(time);
        sighting.setImageUrl(imageUrl);
        sighting.setSpeciesIds(List.copyOf(distinctIds));
        sighting.setObservedAt(observedAt);
        sighting.setTimelinePk(SightingItem.TIMELINE_PK_PREFIX + YearMonth.from(date));
        sighting.setTimelineSk(TimeKeys.sortable(observedAt) + "#" + id);
        sighting.setPrimarySpeciesPk(SightingItem.PRIMARY_SPECIES_PK);
        sighting.setPrimarySpeciesSk(primarySpeciesSortKey(species.get(speciesIds.get(0)).getCommonName(), observedAt, id));
        sighting.setCreatedAt(now);
        sighting.setUpdatedAt(now);

        SightingIdempotencyItem idempotencyItem = new SightingIdempotencyItem();
        idempotencyItem.setPk(SightingIdempotencyItem.keyFor(idempotencyKey));
        idempotencyItem.setIdempotencyKey(idempotencyKey);
        idempotencyItem.setRequestFingerprint(fingerprint);
        idempotencyItem.setSightingId(id);
        idempotencyItem.setCreatedAt(now);

        // A condição na posição 1 (item de idempotência) é o que distingue
        // esta transação de duas requisições concorrentes com a mesma
        // chave: a perdedora recebe TransactionCanceledException e trata
        // isso como replay, não como erro.
        TransactWriteItemsEnhancedRequest.Builder tx = TransactWriteItemsEnhancedRequest.builder()
                .addPutItem(sightingTable, TransactPutItemEnhancedRequest.builder(SightingItem.class)
                        .item(sighting)
                        .conditionExpression(notExists())
                        .build())
                .addPutItem(idempotencyTable, TransactPutItemEnhancedRequest.builder(SightingIdempotencyItem.class)
                        .item(idempotencyItem)
                        .conditionExpression(notExists())
                        .build());

        for (String speciesId : distinctIds) {
            tx.addPutItem(refTable, newRefItem(speciesId, observedAt, id));
            SpeciesItem current = species.get(speciesId);
            current.setReferenceCount(current.getReferenceCount() + 1);
            current.setUpdatedAt(now);
            tx.addUpdateItem(speciesTable, current);
        }

        addNotificationJob(tx, id, now, distinctIds.size());
        try {
            enhancedClient.transactWriteItems(tx.build());
        } catch (TransactionCanceledException e) {
            if (DynamoDbSupport.isConditionFailure(e.cancellationReasons(), 1)) {
                SightingIdempotencyItem winner = getIdempotencyItem(idempotencyKey);
                if (winner != null) {
                    return replayOrConflict(winner, fingerprint);
                }

            }
            if (DynamoDbSupport.hasConditionFailure(e.cancellationReasons())) {
                throw new ConcurrentUpdateException("O registro foi alterado por outra operação. Tente novamente.");
            }
            RuntimeException translated = DynamoDbSupport.translateGeneric(e);
            throw translated != null ? translated : new ConcurrentUpdateException(
                    "O registro foi alterado por outra operação. Tente novamente.");
        } catch (RuntimeException e) {
            RuntimeException translated = DynamoDbSupport.translateGeneric(e);
            throw translated != null ? translated : e;
        }

        return toRecord(sighting);
    }

    @Override
    public Optional<SightingRecord> findById(String id) {
        SightingItem item = sightingTable.getItem(GetItemEnhancedRequest.builder()
                .key(sightingKey(id))
                .consistentRead(true)
                .build());
        return Optional.ofNullable(item).map(this::toRecord);
    }

    private void addNotificationJob(TransactWriteItemsEnhancedRequest.Builder tx, String id, Instant at, int speciesCount) {
        if (4 + 2 * speciesCount > 100) {
            throw new IllegalArgumentException("Limite de espécies excede a capacidade transacional da outbox");
        }
        for (boolean pending : List.of(true, false)) {
            tx.addPutItem(notificationJobs, TransactPutItemEnhancedRequest.builder(NotificationJobItem.class)
                    .item(NotificationJobItem.create(id, at, pending)).conditionExpression(notExists()).build());
        }
    }

    @Override
    public SightingRecord update(String id, List<String> speciesIds, LocalDate date, LocalTime time, String imageUrl) {
        SightingItem current = sightingTable.getItem(GetItemEnhancedRequest.builder()
                .key(sightingKey(id))
                .consistentRead(true)
                .build());
        if (current == null) {
            throw new ResourceNotFoundException("Avistamento não encontrado");
        }

        Set<String> oldIds = new LinkedHashSet<>(current.getSpeciesIds());
        Set<String> newIds = new LinkedHashSet<>(speciesIds);
        Set<String> added = new LinkedHashSet<>(newIds);
        added.removeAll(oldIds);
        Set<String> removed = new LinkedHashSet<>(oldIds);
        removed.removeAll(newIds);

        Map<String, SpeciesItem> touchedSpecies = new LinkedHashMap<>(fetchSpeciesOrThrow(newIds));
        touchedSpecies.putAll(fetchSpeciesOrThrow(removed));

        Instant oldObservedAt = current.getObservedAt();
        Instant newObservedAt = toInstant(date, time);
        Instant now = Instant.now();

        current.setDate(date);
        current.setTime(time);
        current.setImageUrl(imageUrl);
        current.setSpeciesIds(List.copyOf(newIds));
        current.setObservedAt(newObservedAt);
        current.setTimelinePk(SightingItem.TIMELINE_PK_PREFIX + YearMonth.from(date));
        current.setTimelineSk(TimeKeys.sortable(newObservedAt) + "#" + id);
        current.setPrimarySpeciesPk(SightingItem.PRIMARY_SPECIES_PK);
        current.setPrimarySpeciesSk(primarySpeciesSortKey(
                touchedSpecies.get(newIds.iterator().next()).getCommonName(), newObservedAt, id));
        current.setUpdatedAt(now);

        TransactWriteItemsEnhancedRequest.Builder tx = TransactWriteItemsEnhancedRequest.builder()
                .addUpdateItem(sightingTable, current);

        for (String speciesId : oldIds) {
            tx.addDeleteItem(refTable, Key.builder()
                    .partitionValue(SpeciesSightingRefItem.pkFor(speciesId))
                    .sortValue(SpeciesSightingRefItem.skFor(oldObservedAt, id))
                    .build());
        }
        for (String speciesId : newIds) {
            tx.addPutItem(refTable, newRefItem(speciesId, newObservedAt, id));
        }
        for (String speciesId : added) {
            SpeciesItem item = touchedSpecies.get(speciesId);
            item.setReferenceCount(item.getReferenceCount() + 1);
            item.setUpdatedAt(now);
            tx.addUpdateItem(speciesTable, item);
        }
        for (String speciesId : removed) {
            SpeciesItem item = touchedSpecies.get(speciesId);
            item.setReferenceCount(Math.max(0, item.getReferenceCount() - 1));
            item.setUpdatedAt(now);
            tx.addUpdateItem(speciesTable, item);
        }

        executeTransaction(tx.build());

        return toRecord(current);
    }

    @Override
    public void delete(String id) {
        SightingItem current = sightingTable.getItem(GetItemEnhancedRequest.builder()
                .key(sightingKey(id))
                .consistentRead(true)
                .build());
        if (current == null) {
            throw new ResourceNotFoundException("Avistamento não encontrado");
        }

        Set<String> speciesIds = new LinkedHashSet<>(current.getSpeciesIds());
        Map<String, SpeciesItem> species = fetchSpeciesOrThrow(speciesIds);
        Instant now = Instant.now();

        TransactWriteItemsEnhancedRequest.Builder tx = TransactWriteItemsEnhancedRequest.builder()
                .addDeleteItem(sightingTable, sightingKey(id));

        for (String speciesId : speciesIds) {
            tx.addDeleteItem(refTable, Key.builder()
                    .partitionValue(SpeciesSightingRefItem.pkFor(speciesId))
                    .sortValue(SpeciesSightingRefItem.skFor(current.getObservedAt(), id))
                    .build());
            SpeciesItem item = species.get(speciesId);
            item.setReferenceCount(Math.max(0, item.getReferenceCount() - 1));
            item.setUpdatedAt(now);
            tx.addUpdateItem(speciesTable, item);
        }

        executeTransaction(tx.build());
    }

    @Override
    public SightingPage query(SightingQuery query) {
        int limit = Math.max(1, Math.min(query.limit(), properties.maxPageSize()));

        if (query.speciesId() != null) {
            return queryBySpecies(query, limit);
        }
        if (!query.sort().chronological()) {
            return queryByPrimarySpecies(query, limit);
        }
        return queryByTimeline(query, limit);
    }

    // ---- consulta por espécie: partição única, sem necessidade de bucket mensal ----

    private SightingPage queryBySpecies(SightingQuery query, int limit) {
        String pk = SpeciesSightingRefItem.pkFor(query.speciesId());
        QueryConditional condition = dayRangeConditional(pk, query.from(), query.to());

        QueryEnhancedRequest.Builder builder = QueryEnhancedRequest.builder()
                .queryConditional(condition)
                .scanIndexForward(query.sort().ascending())
                .limit(limit);

        Map<String, AttributeValue> exclusiveStart = cursorCodec.decodeSimpleKey(query.cursor());
        if (exclusiveStart != null) {
            builder.exclusiveStartKey(exclusiveStart);
        }

        Page<SpeciesSightingRefItem> page = refTable.query(builder.build()).iterator().next();

        List<String> sightingIds = page.items().stream().map(SpeciesSightingRefItem::getSightingId).toList();
        List<SightingRecord> records = hydrateSightings(sightingIds);

        boolean hasMore = page.lastEvaluatedKey() != null && !page.lastEvaluatedKey().isEmpty();
        String nextCursor = hasMore ? cursorCodec.encodeSimpleKey(page.lastEvaluatedKey()) : null;
        return new SightingPage(records, nextCursor, hasMore);
    }

    private SightingPage queryByPrimarySpecies(SightingQuery query, int limit) {
        QueryEnhancedRequest.Builder builder = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(SightingItem.PRIMARY_SPECIES_PK).build()))
                .scanIndexForward(query.sort().ascending())
                .limit(limit);

        if (query.from() != null) {
            builder.filterExpression(Expression.builder()
                    .expression("#date BETWEEN :from AND :to")
                    .putExpressionName("#date", "date")
                    .putExpressionValue(":from", AttributeValue.builder().s(query.from().toString()).build())
                    .putExpressionValue(":to", AttributeValue.builder().s(query.to().toString()).build())
                    .build());
        }

        Map<String, AttributeValue> exclusiveStart = cursorCodec.decodeSimpleKey(query.cursor());
        if (exclusiveStart != null) {
            builder.exclusiveStartKey(exclusiveStart);
        }

        Page<SightingItem> page = primarySpeciesIndex.query(builder.build()).iterator().next();
        boolean hasMore = page.lastEvaluatedKey() != null && !page.lastEvaluatedKey().isEmpty();
        String nextCursor = hasMore ? cursorCodec.encodeSimpleKey(page.lastEvaluatedKey()) : null;
        return new SightingPage(page.items().stream().map(this::toRecord).toList(), nextCursor, hasMore);
    }

    private QueryConditional dayRangeConditional(String pk, LocalDate from, LocalDate to) {
        if (from != null && to != null) {
            return QueryConditional.sortBetween(
                    Key.builder().partitionValue(pk).sortValue(TimeKeys.sortable(startOfDay(from))).build(),
                    Key.builder().partitionValue(pk).sortValue(TimeKeys.sortable(startOfNextDay(to))).build());
        }
        return QueryConditional.keyEqualTo(Key.builder().partitionValue(pk).build());
    }

    // ---- consulta cronológica sem filtro de espécie: GSI de timeline por mês ----

    private SightingPage queryByTimeline(SightingQuery query, int limit) {
        SightingCursorCodec.TimelineCursorState state = cursorCodec.decodeTimelineCursor(query.cursor());

        String lowerBucket = query.from() != null
                ? YearMonth.from(query.from()).toString()
                : YearMonth.now(ZoneOffset.UTC).minusMonths(properties.maxLookbackMonths()).toString();
        String upperBucket = query.to() != null
                ? YearMonth.from(query.to()).toString()
                : YearMonth.now(ZoneOffset.UTC).toString();
        boolean ascending = query.sort().ascending();

        String bucket = state != null ? state.monthBucket() : (ascending ? lowerBucket : upperBucket);
        Map<String, AttributeValue> exclusiveStart = state != null ? state.lastEvaluatedKey() : null;

        List<SightingItem> collected = new ArrayList<>();
        boolean reachedLowerBoundEnd = false;

        while (collected.size() < limit) {
            if (ascending ? bucket.compareTo(upperBucket) > 0 : bucket.compareTo(lowerBucket) < 0) {
                reachedLowerBoundEnd = true;
                break;
            }

            Page<SightingItem> page = queryTimelineBucket(
                    bucket, exclusiveStart, limit - collected.size(), query, ascending);
            collected.addAll(page.items());

            Map<String, AttributeValue> lastEvaluatedKey = page.lastEvaluatedKey();
            if (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty()) {
                exclusiveStart = lastEvaluatedKey;
                break;
            }
            bucket = (ascending ? YearMonth.parse(bucket).plusMonths(1) : YearMonth.parse(bucket).minusMonths(1)).toString();
            exclusiveStart = null;
        }

        boolean hasMore;
        String nextCursor;
        if (reachedLowerBoundEnd) {
            hasMore = false;
            nextCursor = null;
        } else if (exclusiveStart != null) {
            hasMore = true;
            nextCursor = cursorCodec.encodeTimelineCursor(bucket, exclusiveStart);
        } else {
            hasMore = ascending ? bucket.compareTo(upperBucket) <= 0 : bucket.compareTo(lowerBucket) >= 0;
            nextCursor = hasMore ? cursorCodec.encodeTimelineCursor(bucket, null) : null;
        }

        List<SightingRecord> records = collected.stream().map(this::toRecord).toList();
        return new SightingPage(records, nextCursor, hasMore);
    }

    private Page<SightingItem> queryTimelineBucket(String bucket, Map<String, AttributeValue> exclusiveStart,
                                                     int limitForThisCall, SightingQuery query, boolean ascending) {
        String timelinePk = SightingItem.TIMELINE_PK_PREFIX + bucket;

        String lowerSk = (query.from() != null && bucket.equals(YearMonth.from(query.from()).toString()))
                ? TimeKeys.sortable(startOfDay(query.from())) : null;
        String upperSk = (query.to() != null && bucket.equals(YearMonth.from(query.to()).toString()))
                ? TimeKeys.sortable(startOfNextDay(query.to())) : null;

        QueryConditional condition;
        if (lowerSk != null && upperSk != null) {
            condition = QueryConditional.sortBetween(
                    Key.builder().partitionValue(timelinePk).sortValue(lowerSk).build(),
                    Key.builder().partitionValue(timelinePk).sortValue(upperSk).build());
        } else if (lowerSk != null) {
            condition = QueryConditional.sortGreaterThanOrEqualTo(
                    Key.builder().partitionValue(timelinePk).sortValue(lowerSk).build());
        } else if (upperSk != null) {
            condition = QueryConditional.sortLessThanOrEqualTo(
                    Key.builder().partitionValue(timelinePk).sortValue(upperSk).build());
        } else {
            condition = QueryConditional.keyEqualTo(Key.builder().partitionValue(timelinePk).build());
        }

        QueryEnhancedRequest.Builder builder = QueryEnhancedRequest.builder()
                .queryConditional(condition)
                .scanIndexForward(ascending)
                .limit(limitForThisCall);
        if (exclusiveStart != null) {
            builder.exclusiveStartKey(exclusiveStart);
        }

        return timelineIndex.query(builder.build()).iterator().next();
    }

    private String primarySpeciesSortKey(String name, Instant observedAt, String id) {
        return normalizeSpeciesName(name) + "#" + TimeKeys.sortable(observedAt) + "#" + id;
    }

    private String normalizeSpeciesName(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    // ---- helpers compartilhados ----

    private Map<String, SpeciesItem> fetchSpeciesOrThrow(Collection<String> speciesIds) {
        if (speciesIds.isEmpty()) {
            return Map.of();
        }
        ReadBatch.Builder<SpeciesItem> batch = ReadBatch.builder(SpeciesItem.class).mappedTableResource(speciesTable);
        speciesIds.forEach(id -> batch.addGetItem(Key.builder().partitionValue(SpeciesItem.keyFor(id)).build()));

        Map<String, SpeciesItem> byId = new LinkedHashMap<>();
        enhancedClient.batchGetItem(BatchGetItemEnhancedRequest.builder().readBatches(batch.build()).build())
                .resultsForTable(speciesTable)
                .forEach(item -> byId.put(item.getId(), item));

        for (String id : speciesIds) {
            if (!byId.containsKey(id)) {
                throw new ResourceNotFoundException("Espécie não encontrada: id " + id);
            }
        }
        return byId;
    }

    private List<SightingRecord> hydrateSightings(List<String> sightingIds) {
        if (sightingIds.isEmpty()) {
            return List.of();
        }
        ReadBatch.Builder<SightingItem> batch = ReadBatch.builder(SightingItem.class).mappedTableResource(sightingTable);
        sightingIds.forEach(id -> batch.addGetItem(sightingKey(id)));

        Map<String, SightingItem> byId = new LinkedHashMap<>();
        enhancedClient.batchGetItem(BatchGetItemEnhancedRequest.builder().readBatches(batch.build()).build())
                .resultsForTable(sightingTable)
                .forEach(item -> byId.put(item.getId(), item));

        return sightingIds.stream().map(byId::get).filter(Objects::nonNull).map(this::toRecord).toList();
    }

    private void executeTransaction(TransactWriteItemsEnhancedRequest request) {
        try {
            enhancedClient.transactWriteItems(request);
        } catch (TransactionCanceledException e) {
            if (DynamoDbSupport.hasConditionFailure(e.cancellationReasons())) {
                throw new ConcurrentUpdateException("O registro foi alterado por outra operação. Tente novamente.");
            }
            RuntimeException translated = DynamoDbSupport.translateGeneric(e);
            throw translated != null ? translated : new ConcurrentUpdateException(
                    "O registro foi alterado por outra operação. Tente novamente.");
        } catch (RuntimeException e) {
            RuntimeException translated = DynamoDbSupport.translateGeneric(e);
            throw translated != null ? translated : e;
        }
    }

    private static SpeciesSightingRefItem newRefItem(String speciesId, Instant observedAt, String sightingId) {
        SpeciesSightingRefItem ref = new SpeciesSightingRefItem();
        ref.setPk(SpeciesSightingRefItem.pkFor(speciesId));
        ref.setSk(SpeciesSightingRefItem.skFor(observedAt, sightingId));
        ref.setSpeciesId(speciesId);
        ref.setSightingId(sightingId);
        ref.setObservedAt(observedAt);
        return ref;
    }

    private static Key sightingKey(String id) {
        return Key.builder().partitionValue(SightingItem.keyFor(id)).sortValue(SightingItem.DETAILS_SORT_KEY).build();
    }

    private SightingIdempotencyItem getIdempotencyItem(String idempotencyKey) {
        return idempotencyTable.getItem(GetItemEnhancedRequest.builder()
                .key(Key.builder()
                        .partitionValue(SightingIdempotencyItem.keyFor(idempotencyKey))
                        .sortValue(SightingIdempotencyItem.SORT_KEY)
                        .build())
                .consistentRead(true)
                .build());
    }

    /**
     * Repetição da mesma chave com o mesmo payload devolve o avistamento
     * original; com um payload diferente, é rejeitada como conflito, para
     * nunca associar silenciosamente uma chave de idempotência a dois
     * resultados distintos.
     */
    private SightingRecord replayOrConflict(SightingIdempotencyItem existing, String fingerprint) {
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw new DuplicateResourceException(
                    "Idempotency-Key já foi usada com uma requisição diferente");
        }
        SightingItem sighting = sightingTable.getItem(GetItemEnhancedRequest.builder()
                .key(sightingKey(existing.getSightingId()))
                .consistentRead(true)
                .build());
        if (sighting == null) {
            throw new ResourceNotFoundException(
                    "Avistamento associado à chave de idempotência não foi encontrado");
        }
        return toRecord(sighting);
    }

    /** Impressão digital do payload usada para detectar reuso de chave com dados divergentes. */
    private static String computeFingerprint(List<String> speciesIds, LocalDate date, LocalTime time, String imageUrl) {
        List<String> sortedIds = speciesIds.stream().sorted().toList();
        String canonical = String.join("|", sortedIds) + "||" + date + "|" + time + "|"
                + (imageUrl == null ? "" : imageUrl);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 não disponível na JVM", e);
        }
    }

    private static Instant toInstant(LocalDate date, LocalTime time) {
        return date.atTime(time).toInstant(ZoneOffset.UTC);
    }

    private static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant startOfNextDay(LocalDate date) {
        return date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Expression notExists() {
        return Expression.builder()
                .expression("attribute_not_exists(pk)")
                .build();
    }

    private SightingRecord toRecord(SightingItem item) {
        return new SightingRecord(
                item.getId(),
                item.getSpeciesIds(),
                item.getDate(),
                item.getTime(),
                item.getImageUrl(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }
}
