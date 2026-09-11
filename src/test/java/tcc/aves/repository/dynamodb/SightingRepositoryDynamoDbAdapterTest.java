package tcc.aves.repository.dynamodb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import tcc.aves.config.SightingProperties;
import tcc.aves.exception.ConcurrentUpdateException;
import tcc.aves.exception.ResourceNotFoundException;
import tcc.aves.repository.SightingPage;
import tcc.aves.repository.SightingQuery;
import tcc.aves.repository.SightingRecord;
import tcc.aves.repository.SpeciesRecord;
import tcc.aves.repository.dynamodb.model.SightingItem;
import tcc.aves.repository.dynamodb.model.SpeciesItem;
import tcc.aves.repository.dynamodb.model.SpeciesSightingRefItem;
import tcc.aves.repository.dynamodb.support.SightingCursorCodec;
import tcc.aves.repository.dynamodb.support.TimeKeys;
import tcc.aves.support.AbstractDynamoDbLocalTest;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@Disabled("Requires the amazon/dynamodb-local Docker image, which this environment cannot pull/run reliably.")
class SightingRepositoryDynamoDbAdapterTest extends AbstractDynamoDbLocalTest {

    private static final String USERS_TABLE = "it-sightings-users";
    private static final String SPECIES_TABLE = "it-sightings-species";
    private static final String SIGHTINGS_TABLE = "it-sightings-sightings";

    private SpeciesRepositoryDynamoDbAdapter speciesRepository;
    private SightingRepositoryDynamoDbAdapter sightingRepository;

    @BeforeAll
    void setUpClient() {
        initializeClients();
    }

    @BeforeEach
    void setUpRepositories() {
        recreateTables(USERS_TABLE, SPECIES_TABLE, SIGHTINGS_TABLE);
        speciesRepository = new SpeciesRepositoryDynamoDbAdapter(
                enhancedClient,
                speciesTable(SPECIES_TABLE),
                scientificReservationTable(SPECIES_TABLE));
        sightingRepository = newRepository(sightingTable(SIGHTINGS_TABLE));
    }

    @Test
    void createPersistsDetailsReferenceItemsAndReferenceCounts() {
        SpeciesRecord arara = speciesRepository.create("Arara", "Ara ararauna", "Desc", "Tips", "https://img/a");
        SpeciesRecord tucano = speciesRepository.create("Tucano", "Ramphastos toco", "Desc", "Tips", "https://img/t");

        SightingRecord created = sightingRepository.create(
                List.of(arara.id(), tucano.id()),
                LocalDate.of(2026, 9, 10),
                LocalTime.of(10, 15, 0, 500_000_000),
                "https://sighting/1");

        Instant observedAt = created.date().atTime(created.time()).toInstant(java.time.ZoneOffset.UTC);
        assertFalse(getItem(SIGHTINGS_TABLE, SightingItem.keyFor(created.id()), SightingItem.DETAILS_SORT_KEY).isEmpty());
        assertFalse(getItem(SIGHTINGS_TABLE, SpeciesSightingRefItem.pkFor(arara.id()), SpeciesSightingRefItem.skFor(observedAt, created.id())).isEmpty());
        assertFalse(getItem(SIGHTINGS_TABLE, SpeciesSightingRefItem.pkFor(tucano.id()), SpeciesSightingRefItem.skFor(observedAt, created.id())).isEmpty());
        assertEquals(1L, speciesRepository.findById(arara.id()).orElseThrow().referenceCount());
        assertEquals(1L, speciesRepository.findById(tucano.id()).orElseThrow().referenceCount());
        assertEquals(3, countItems(SIGHTINGS_TABLE));
    }

    @Test
    void createRejectsMissingSpeciesWithoutPartialWrites() {
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> sightingRepository.create(List.of("missing-species"), LocalDate.of(2026, 9, 10), LocalTime.NOON, "https://sighting"));

        assertEquals("Espécie não encontrada: id missing-species", exception.getMessage());
        assertEquals(0, countItems(SIGHTINGS_TABLE));
    }

    @Test
    void updateReplacesReferenceItemsAndAdjustsReferenceCounts() {
        SpeciesRecord arara = speciesRepository.create("Arara", "Ara ararauna", "Desc", "Tips", "https://img/a");
        SpeciesRecord tucano = speciesRepository.create("Tucano", "Ramphastos toco", "Desc", "Tips", "https://img/t");
        SpeciesRecord coruja = speciesRepository.create("Coruja", "Tyto alba", "Desc", "Tips", "https://img/c");
        SightingRecord created = sightingRepository.create(
                List.of(arara.id(), tucano.id()),
                LocalDate.of(2026, 8, 31),
                LocalTime.of(23, 59, 59),
                "https://sighting/1");

        Instant oldObservedAt = created.date().atTime(created.time()).toInstant(java.time.ZoneOffset.UTC);
        SightingRecord updated = sightingRepository.update(
                created.id(),
                List.of(tucano.id(), coruja.id()),
                LocalDate.of(2026, 9, 1),
                LocalTime.of(0, 0, 0, 500_000_000),
                "https://sighting/2");
        Instant newObservedAt = updated.date().atTime(updated.time()).toInstant(java.time.ZoneOffset.UTC);

        assertTrue(getItem(SIGHTINGS_TABLE, SpeciesSightingRefItem.pkFor(arara.id()), SpeciesSightingRefItem.skFor(oldObservedAt, created.id())).isEmpty());
        assertTrue(getItem(SIGHTINGS_TABLE, SpeciesSightingRefItem.pkFor(tucano.id()), SpeciesSightingRefItem.skFor(oldObservedAt, created.id())).isEmpty());
        assertFalse(getItem(SIGHTINGS_TABLE, SpeciesSightingRefItem.pkFor(tucano.id()), SpeciesSightingRefItem.skFor(newObservedAt, created.id())).isEmpty());
        assertFalse(getItem(SIGHTINGS_TABLE, SpeciesSightingRefItem.pkFor(coruja.id()), SpeciesSightingRefItem.skFor(newObservedAt, created.id())).isEmpty());
        assertEquals(0L, speciesRepository.findById(arara.id()).orElseThrow().referenceCount());
        assertEquals(1L, speciesRepository.findById(tucano.id()).orElseThrow().referenceCount());
        assertEquals(1L, speciesRepository.findById(coruja.id()).orElseThrow().referenceCount());
    }

    @Test
    void deleteRemovesDetailsRefsAndDecrementsReferenceCounts() {
        SpeciesRecord arara = speciesRepository.create("Arara", "Ara ararauna", "Desc", "Tips", "https://img/a");
        SpeciesRecord tucano = speciesRepository.create("Tucano", "Ramphastos toco", "Desc", "Tips", "https://img/t");
        SightingRecord created = sightingRepository.create(
                List.of(arara.id(), tucano.id()),
                LocalDate.of(2026, 9, 10),
                LocalTime.of(10, 15),
                "https://sighting/1");
        Instant observedAt = created.date().atTime(created.time()).toInstant(java.time.ZoneOffset.UTC);

        sightingRepository.delete(created.id());

        assertTrue(getItem(SIGHTINGS_TABLE, SightingItem.keyFor(created.id()), SightingItem.DETAILS_SORT_KEY).isEmpty());
        assertTrue(getItem(SIGHTINGS_TABLE, SpeciesSightingRefItem.pkFor(arara.id()), SpeciesSightingRefItem.skFor(observedAt, created.id())).isEmpty());
        assertTrue(getItem(SIGHTINGS_TABLE, SpeciesSightingRefItem.pkFor(tucano.id()), SpeciesSightingRefItem.skFor(observedAt, created.id())).isEmpty());
        assertEquals(0L, speciesRepository.findById(arara.id()).orElseThrow().referenceCount());
        assertEquals(0L, speciesRepository.findById(tucano.id()).orElseThrow().referenceCount());
    }

    @Test
    void concurrentUpdatesSurfaceOptimisticLockConflict() throws Exception {
        SpeciesRecord arara = speciesRepository.create("Arara", "Ara ararauna", "Desc", "Tips", "https://img/a");
        SightingRecord created = sightingRepository.create(List.of(arara.id()), LocalDate.of(2026, 9, 10), LocalTime.of(10, 15), "https://sighting/1");

        CountDownLatch bothReadsDone = new CountDownLatch(2);
        CountDownLatch releaseUpdates = new CountDownLatch(1);
        SightingRepositoryDynamoDbAdapter repositoryA = newRepository(blockingSightingTable(bothReadsDone, releaseUpdates));
        SightingRepositoryDynamoDbAdapter repositoryB = newRepository(blockingSightingTable(bothReadsDone, releaseUpdates));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SightingRecord> first = executor.submit(updateCall(repositoryA, created.id(), arara.id(), "https://sighting/a"));
            Future<SightingRecord> second = executor.submit(updateCall(repositoryB, created.id(), arara.id(), "https://sighting/b"));

            bothReadsDone.await();
            releaseUpdates.countDown();

            int successes = 0;
            int conflicts = 0;
            for (Future<SightingRecord> future : List.of(first, second)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException ex) {
                    assertInstanceOf(ConcurrentUpdateException.class, ex.getCause());
                    conflicts++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, conflicts);
        }
    }

    @Test
    void queryPaginatesAcrossSpeciesTimelineAndDateBoundaries() {
        SpeciesRecord arara = speciesRepository.create("Arara", "Ara ararauna", "Desc", "Tips", "https://img/a");
        SpeciesRecord tucano = speciesRepository.create("Tucano", "Ramphastos toco", "Desc", "Tips", "https://img/t");

        SightingRecord septemberMidnight = sightingRepository.create(List.of(arara.id()), LocalDate.of(2026, 9, 1), LocalTime.MIDNIGHT, "https://s/1");
        SightingRecord augustHalfSecond = sightingRepository.create(List.of(arara.id()), LocalDate.of(2026, 8, 31), LocalTime.of(23, 59, 59, 500_000_000), "https://s/2");
        SightingRecord augustWholeSecond = sightingRepository.create(List.of(arara.id(), tucano.id()), LocalDate.of(2026, 8, 31), LocalTime.of(23, 59, 59), "https://s/3");
        SightingRecord augustOlder = sightingRepository.create(List.of(tucano.id()), LocalDate.of(2026, 8, 15), LocalTime.NOON, "https://s/4");

        SightingPage speciesPage1 = sightingRepository.query(new SightingQuery(arara.id(), null, null, null, 2));
        assertEquals(List.of(septemberMidnight.id(), augustHalfSecond.id()), speciesPage1.items().stream().map(SightingRecord::id).toList());
        assertTrue(speciesPage1.hasMore());
        assertNotNull(speciesPage1.nextCursor());

        SightingPage speciesPage2 = sightingRepository.query(new SightingQuery(arara.id(), null, null, speciesPage1.nextCursor(), 2));
        assertEquals(List.of(augustWholeSecond.id()), speciesPage2.items().stream().map(SightingRecord::id).toList());

        SightingPage timelinePage1 = sightingRepository.query(new SightingQuery(null, null, null, null, 2));
        assertEquals(List.of(septemberMidnight.id(), augustHalfSecond.id()), timelinePage1.items().stream().map(SightingRecord::id).toList());
        assertTrue(timelinePage1.hasMore());
        assertNotNull(timelinePage1.nextCursor());

        SightingPage timelinePage2 = sightingRepository.query(new SightingQuery(null, null, null, timelinePage1.nextCursor(), 2));
        assertEquals(List.of(augustWholeSecond.id(), augustOlder.id()), timelinePage2.items().stream().map(SightingRecord::id).toList());
        assertTrue(TimeKeys.sortable(Instant.parse("2026-08-31T23:59:59.000Z")).compareTo(TimeKeys.sortable(Instant.parse("2026-08-31T23:59:59.500Z"))) < 0);

        SightingPage august31Only = sightingRepository.query(new SightingQuery(arara.id(), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31), null, 10));
        assertEquals(List.of(augustHalfSecond.id(), augustWholeSecond.id()), august31Only.items().stream().map(SightingRecord::id).toList());
    }

    private Callable<SightingRecord> updateCall(SightingRepositoryDynamoDbAdapter repository,
                                                String sightingId,
                                                String speciesId,
                                                String imageUrl) {
        return () -> repository.update(sightingId, List.of(speciesId), LocalDate.of(2026, 9, 10), LocalTime.of(10, 20), imageUrl);
    }

    @SuppressWarnings("unchecked")
    private software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable<SightingItem> blockingSightingTable(
            CountDownLatch bothReadsDone,
            CountDownLatch releaseUpdates) {
        software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable<SightingItem> delegate = sightingTable(SIGHTINGS_TABLE);
        return (software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable<SightingItem>) Proxy.newProxyInstance(
                delegate.getClass().getClassLoader(),
                new Class<?>[]{software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getItem") && args != null && args.length == 1) {
                        Object result = method.invoke(delegate, args);
                        bothReadsDone.countDown();
                        releaseUpdates.await();
                        return result;
                    }
                    return method.invoke(delegate, args);
                });
    }

    private SightingRepositoryDynamoDbAdapter newRepository(software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable<SightingItem> sightingTable) {
        return new SightingRepositoryDynamoDbAdapter(
                enhancedClient,
                sightingTable,
                speciesSightingRefTable(SIGHTINGS_TABLE),
                sightingTimelineIndex(SIGHTINGS_TABLE),
                speciesTable(SPECIES_TABLE),
                new SightingProperties(20, 50, 20, 60),
                new SightingCursorCodec(new ObjectMapper()));
    }
}
