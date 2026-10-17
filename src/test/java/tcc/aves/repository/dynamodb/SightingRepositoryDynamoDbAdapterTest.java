package tcc.aves.repository.dynamodb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import tcc.aves.config.SightingProperties;
import tcc.aves.exception.ConcurrentUpdateException;
import tcc.aves.exception.ResourceNotFoundException;
import tcc.aves.repository.SightingPage;
import tcc.aves.repository.SightingQuery;
import tcc.aves.repository.SightingRecord;
import tcc.aves.repository.SightingSort;
import tcc.aves.repository.SpeciesRecord;
import tcc.aves.repository.dynamodb.model.SightingIdempotencyItem;
import tcc.aves.repository.dynamodb.model.SightingItem;
import tcc.aves.repository.dynamodb.model.NotificationJobItem;
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
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
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
        assertEquals(8, countItems(SIGHTINGS_TABLE));
    }

    @Test
    void createRejectsMissingSpeciesWithoutPartialWrites() {
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> sightingRepository.create(List.of("missing-species"), LocalDate.of(2026, 9, 10), LocalTime.NOON, "https://sighting"));

        assertEquals("Espécie não encontrada: id missing-species", exception.getMessage());
        assertEquals(0, countItems(SIGHTINGS_TABLE));
    }

    @Test
    void createIdempotentPersistsIdempotencyItemAlongsideSighting() {
        SpeciesRecord arara = speciesRepository.create("Arara", "Ara ararauna", "Desc", "Tips", "https://img/a");

        SightingRecord created = sightingRepository.createIdempotent(
                "worker-key-1", List.of(arara.id()), LocalDate.of(2026, 9, 10), LocalTime.of(10, 15), "https://sighting/1");

        assertFalse(getItem(SIGHTINGS_TABLE, SightingIdempotencyItem.keyFor("worker-key-1"), SightingIdempotencyItem.SORT_KEY).isEmpty());
        assertEquals(1L, speciesRepository.findById(arara.id()).orElseThrow().referenceCount());
        assertEquals(8, countItems(SIGHTINGS_TABLE));
        assertNotNull(created.id());
    }

    @Test
    void createIdempotentReplaysSameSightingForSameKeyAndPayload() {
        SpeciesRecord arara = speciesRepository.create("Arara", "Ara ararauna", "Desc", "Tips", "https://img/a");

        SightingRecord first = sightingRepository.createIdempotent(
                "worker-key-2", List.of(arara.id()), LocalDate.of(2026, 9, 10), LocalTime.of(10, 15), "https://sighting/1");
        SightingRecord replay = sightingRepository.createIdempotent(
                "worker-key-2", List.of(arara.id()), LocalDate.of(2026, 9, 10), LocalTime.of(10, 15), "https://sighting/1");

        assertEquals(first.id(), replay.id());
        assertEquals(1L, speciesRepository.findById(arara.id()).orElseThrow().referenceCount());
        assertEquals(8, countItems(SIGHTINGS_TABLE));
    }

    @Test
    void createIdempotentRejectsSameKeyWithDifferentPayload() {
        SpeciesRecord arara = speciesRepository.create("Arara", "Ara ararauna", "Desc", "Tips", "https://img/a");
        sightingRepository.createIdempotent(
                "worker-key-3", List.of(arara.id()), LocalDate.of(2026, 9, 10), LocalTime.of(10, 15), "https://sighting/1");

        tcc.aves.exception.DuplicateResourceException exception = assertThrows(
                tcc.aves.exception.DuplicateResourceException.class,
                () -> sightingRepository.createIdempotent(
                        "worker-key-3", List.of(arara.id()), LocalDate.of(2026, 9, 10), LocalTime.of(10, 15), "https://sighting/DIFFERENT"));

        assertEquals("Idempotency-Key já foi usada com uma requisição diferente", exception.getMessage());
    }

    @Test
    void createDoesNotQueueNotificationsForUnidentifiedOnlySightings() {
        for (int index = 0; index < 3; index++) {
            String name = List.of("Outros", "Ave não identificada", "Espécie não identificada").get(index);
            SpeciesRecord species = speciesRepository.create(
                    name, "Avis incognita " + index, "Desc", "Tips", "https://img/" + index);

            SightingRecord created = sightingRepository.create(
                    List.of(species.id()),
                    LocalDate.of(2026, 9, 10),
                    LocalTime.of(10, 15, index),
                    "https://sighting/unidentified-" + index);

            assertTrue(getItem(SIGHTINGS_TABLE, NotificationJobItem.PENDING,
                    NotificationJobItem.order(created.createdAt(), created.id())).isEmpty());
            assertTrue(getItem(SIGHTINGS_TABLE, "NOTIF#EVENT#" + created.id(), "STATE").isEmpty());
        }
    }

    @Test
    void createQueuesNotificationsForMixedSightingsWithAnIdentifiedSpecies() {
        SpeciesRecord unidentified = speciesRepository.create(
                "Ave não identificada", "Avis incognita", "Desc", "Tips", "https://img/u");
        SpeciesRecord arara = speciesRepository.create(
                "Arara", "Ara ararauna", "Desc", "Tips", "https://img/a");

        SightingRecord created = sightingRepository.create(
                List.of(unidentified.id(), arara.id()),
                LocalDate.of(2026, 9, 10),
                LocalTime.of(10, 15),
                "https://sighting/mixed");

        assertFalse(getItem(SIGHTINGS_TABLE, NotificationJobItem.PENDING,
                NotificationJobItem.order(created.createdAt(), created.id())).isEmpty());
        assertFalse(getItem(SIGHTINGS_TABLE, "NOTIF#EVENT#" + created.id(), "STATE").isEmpty());
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
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        SightingRepositoryDynamoDbAdapter repositoryA = newRepository(blockingSightingTable(bothReadsDone, releaseFirst));
        SightingRepositoryDynamoDbAdapter repositoryB = newRepository(blockingSightingTable(bothReadsDone, releaseSecond));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SightingRecord> first = executor.submit(updateCall(repositoryA, created.id(), arara.id(), "https://sighting/a"));
            Future<SightingRecord> second = executor.submit(updateCall(repositoryB, created.id(), arara.id(), "https://sighting/b"));

            bothReadsDone.await();
            releaseFirst.countDown();
            try {
                first.get();
            } finally {
                releaseSecond.countDown();
            }

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
        SightingRecord augustWholeSecond = sightingRepository.create(List.of(arara.id(), tucano.id()), LocalDate.of(2026, 8, 31), LocalTime.of(23, 58, 59), "https://s/3");
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

    @Test
    void queryOrdersByFirstSpeciesAndOldestChronologically() {
        SpeciesRecord arara = speciesRepository.create("Arara", "Ara ararauna", "Desc", "Tips", "https://img/a");
        SpeciesRecord beija = speciesRepository.create("Beija-flor", "Heliomaster squamosus", "Desc", "Tips", "https://img/b");
        SpeciesRecord tucano = speciesRepository.create("Tucano", "Ramphastos toco", "Desc", "Tips", "https://img/t");

        SightingRecord newest = sightingRepository.create(List.of(tucano.id()), LocalDate.of(2026, 9, 3), LocalTime.NOON, "https://s/1");
        SightingRecord middle = sightingRepository.create(List.of(arara.id(), tucano.id()), LocalDate.of(2026, 9, 2), LocalTime.NOON, "https://s/2");
        SightingRecord oldest = sightingRepository.create(List.of(beija.id()), LocalDate.of(2026, 9, 1), LocalTime.NOON, "https://s/3");

        SightingPage speciesAscending = sightingRepository.query(
                new SightingQuery(null, null, null, null, 10, SightingSort.SPECIES_ASC));
        assertEquals(List.of(middle.id(), oldest.id(), newest.id()),
                speciesAscending.items().stream().map(SightingRecord::id).toList());

        SightingPage speciesDescending = sightingRepository.query(
                new SightingQuery(null, null, null, null, 10, SightingSort.SPECIES_DESC));
        assertEquals(List.of(newest.id(), oldest.id(), middle.id()),
                speciesDescending.items().stream().map(SightingRecord::id).toList());

        SightingPage chronologicalAscending = sightingRepository.query(
                new SightingQuery(null, null, null, null, 10, SightingSort.OLDEST));
        assertEquals(List.of(oldest.id(), middle.id(), newest.id()),
                chronologicalAscending.items().stream().map(SightingRecord::id).toList());
    }

    @Test
    void groupsByWholeSpeciesSetAndMinuteAndRollsOverAfterFourPhotos() {
        SpeciesRecord a = speciesRepository.create("Arara", "Ara ararauna", null, null, null);
        SpeciesRecord b = speciesRepository.create("Tucano", "Ramphastos toco", null, null, null);
        LocalDate day = LocalDate.of(2026, 9, 10);
        List<SightingRecord> accepted = new ArrayList<>();
        for (int n = 0; n < 9; n++) {
            accepted.add(sightingRepository.createIdempotent("photo-" + n,
                    n % 2 == 0 ? List.of(a.id(), b.id()) : List.of(b.id(), a.id()),
                    day, LocalTime.of(10, 15, n, n * 100), "https://synthetic.test/" + n));
        }
        assertEquals(3, accepted.stream().map(SightingRecord::id).distinct().count());
        SightingRecord first = sightingRepository.findById(accepted.getFirst().id()).orElseThrow();
        assertEquals(4, first.imageUrls().size());
        assertEquals(List.of(a.id(), b.id()), first.speciesIds());
        assertEquals(LocalTime.of(10, 15), first.time());
        assertEquals(first.imageUrls().getFirst(), first.imageUrl());
        assertEquals(3, speciesRepository.findById(a.id()).orElseThrow().referenceCount());
        assertEquals(3, speciesRepository.findById(b.id()).orElseThrow().referenceCount());
        assertEquals(first.id(), sightingRepository.createIdempotent("other-key", List.of(a.id(), b.id()),
                day, LocalTime.of(10, 15, 59), first.imageUrl()).id());
        assertEquals(first.id(), sightingRepository.createIdempotent("photo-0", List.of(a.id(), b.id()),
                day, LocalTime.of(10, 15), first.imageUrl()).id());
        assertNotEquals(first.id(), sightingRepository.create(List.of(a.id()), day, LocalTime.of(10, 15), first.imageUrl()).id());
        assertNotEquals(first.id(), sightingRepository.create(List.of(a.id(), b.id()), day, LocalTime.of(10, 16), first.imageUrl()).id());
        assertEquals(3, enhancedClient.table(SIGHTINGS_TABLE,
                software.amazon.awssdk.enhanced.dynamodb.TableSchema.fromBean(tcc.aves.repository.dynamodb.model.NotificationJobItem.class))
                .query(software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional.keyEqualTo(
                        software.amazon.awssdk.enhanced.dynamodb.Key.builder().partitionValue("NOTIF#PENDING").build()))
                .items().stream().filter(item -> accepted.stream().anyMatch(record -> record.id().equals(item.getSightingId()))).count());
    }

    @Test
    void concurrentPhotosDoNotLoseImagesOrExceedCapacity() throws Exception {
        SpeciesRecord a = speciesRepository.create("Arara", "Ara ararauna", null, null, null);
        List<Callable<SightingRecord>> calls = new ArrayList<>();
        for (int n = 0; n < 9; n++) {
            int photo = n;
            calls.add(() -> sightingRepository.createIdempotent("parallel-" + photo, List.of(a.id()),
                    LocalDate.of(2026, 9, 10), LocalTime.of(10, 15, photo), "https://synthetic.test/" + photo));
        }
        try (var workers = Executors.newFixedThreadPool(4)) {
            Set<String> ids = new java.util.HashSet<>();
            for (var result : workers.invokeAll(calls)) ids.add(result.get().id());
            assertEquals(3, ids.size());
            Set<String> images = new java.util.HashSet<>();
            for (String id : ids) {
                var record = sightingRepository.findById(id).orElseThrow();
                assertTrue(record.imageUrls().size() <= 4);
                images.addAll(record.imageUrls());
            }
            assertEquals(9, images.size());
            assertEquals(3, speciesRepository.findById(a.id()).orElseThrow().referenceCount());
        }
    }

    @Test
    void sameMinuteUpdateKeepsReferencesAndRejectsDestructiveLegacyGalleryEdit() {
        SpeciesRecord a = speciesRepository.create("Arara", "Ara ararauna", null, null, null);
        LocalDate day = LocalDate.of(2026, 9, 10);
        var first = sightingRepository.create(List.of(a.id()), day, LocalTime.NOON, "https://synthetic.test/a");
        sightingRepository.create(List.of(a.id()), day, LocalTime.of(12, 0, 59), "https://synthetic.test/b");
        var updated = sightingRepository.update(first.id(), List.of(a.id()), day, LocalTime.of(12, 0, 30), first.imageUrl());
        assertEquals(2, updated.imageUrls().size());
        assertEquals(1, speciesRepository.findById(a.id()).orElseThrow().referenceCount());
        assertThrows(tcc.aves.exception.DuplicateResourceException.class,
                () -> sightingRepository.update(first.id(), List.of(a.id()), day, LocalTime.NOON, null));
        assertEquals(List.of("https://synthetic.test/c"), sightingRepository.updateGallery(first.id(),
                List.of(a.id()), day, LocalTime.NOON, List.of("https://synthetic.test/c")).imageUrls());
    }

    @Test
    void correctionCollisionIsAtomicAndDeletingActiveMemberKeepsRemainingGroupUsable() {
        SpeciesRecord a = speciesRepository.create("Arara", "Ara ararauna", null, null, null);
        LocalDate day = LocalDate.of(2026, 9, 10);
        var first = sightingRepository.create(List.of(a.id()), day, LocalTime.NOON, "https://synthetic.test/0");
        for (int n = 1; n < 4; n++) sightingRepository.create(List.of(a.id()), day, LocalTime.NOON, "https://synthetic.test/" + n);
        var second = sightingRepository.create(List.of(a.id()), day, LocalTime.NOON, "https://synthetic.test/4");
        var elsewhere = sightingRepository.create(List.of(a.id()), day, LocalTime.of(12, 1), "https://synthetic.test/5");
        assertThrows(tcc.aves.exception.DuplicateResourceException.class, () -> sightingRepository.update(
                elsewhere.id(), List.of(a.id()), day, LocalTime.NOON, elsewhere.imageUrl()));
        assertEquals(LocalTime.of(12, 1), sightingRepository.findById(elsewhere.id()).orElseThrow().time());
        sightingRepository.delete(second.id());
        assertEquals(4, sightingRepository.findById(first.id()).orElseThrow().imageUrls().size());
        var next = sightingRepository.create(List.of(a.id()), day, LocalTime.NOON, "https://synthetic.test/6");
        assertNotEquals(first.id(), next.id());
        assertNotEquals(second.id(), next.id());
        assertEquals(3, speciesRepository.findById(a.id()).orElseThrow().referenceCount());
    }

    @Test
    void historicalMigrationUsesRealDynamoDbAndPreservesAliasesAndAllowsAppend() throws Exception {
        SpeciesRecord species = speciesRepository.create("Synthetic bird", "Synthetic avis", null, null, null);
        var speciesItem = speciesTable(SPECIES_TABLE).getItem(
                software.amazon.awssdk.enhanced.dynamodb.Key.builder().partitionValue("SPECIES#" + species.id()).build());
        speciesItem.setReferenceCount(2L);
        speciesTable(SPECIES_TABLE).updateItem(speciesItem);
        for (int i = 0; i < 2; i++) {
            SightingItem legacy = new SightingItem();
            legacy.setId("legacy-" + i);
            legacy.setPk(SightingItem.keyFor(legacy.getId()));
            legacy.setDate(LocalDate.of(2026, 9, 10));
            legacy.setTime(LocalTime.of(10, 15, i));
            legacy.setSpeciesIds(List.of(species.id()));
            legacy.setImageUrl("https://synthetic.test/" + i);
            legacy.setObservedAt(legacy.getDate().atTime(legacy.getTime()).toInstant(java.time.ZoneOffset.UTC));
            legacy.setCreatedAt(legacy.getObservedAt());
            legacy.setUpdatedAt(legacy.getObservedAt());
            sightingTable(SIGHTINGS_TABLE).putItem(legacy);
            SpeciesSightingRefItem ref = new SpeciesSightingRefItem();
            ref.setPk("SPECIES#" + species.id());
            ref.setSk(SpeciesSightingRefItem.skFor(legacy.getObservedAt(), legacy.getId()));
            ref.setSpeciesId(species.id());
            ref.setSightingId(legacy.getId());
            ref.setObservedAt(legacy.getObservedAt());
            speciesSightingRefTable(SIGHTINGS_TABLE).putItem(ref);
        }
        String source = """
                import {buildManifest,applyManifest,awsStore} from './scripts/migrate-sighting-groups.mjs';
                const store=awsStore({endpoint:'%s',region:'us-east-1'});
                const snapshot={target:{account:'LOCAL',region:'us-east-1',sightingsTable:'%s',speciesTable:'%s'},
                  capturedAt:'2026-10-01T00:00:00Z',sightings:store.scan('%s'),species:store.scan('%s')};
                const manifest=buildManifest(snapshot);
                const journal={approvalHash:manifest.approvalHash,completed:[],reverted:[]};
                applyManifest(manifest,store,journal,()=>{});
                applyManifest(manifest,store,journal,()=>{});
                const detail=store.get('%s',{pk:{S:'SIGHTING#legacy-0'},sk:{S:'DETAILS'}});
                const alias=store.get('%s',{pk:{S:'SIGHTING_ALIAS#legacy-1'},sk:{S:'TARGET'}});
                console.log(JSON.stringify({photos:detail.imageUrls.L.length,target:alias.sightingId.S}));
                """.formatted(endpointOverride(), SIGHTINGS_TABLE, SPECIES_TABLE, SIGHTINGS_TABLE, SPECIES_TABLE,
                SIGHTINGS_TABLE, SIGHTINGS_TABLE);
        Process process = new ProcessBuilder("node", "--input-type=module", "--eval", source).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        assertTrue(output.contains("\"photos\":2"), output);
        assertTrue(output.contains("\"target\":\"legacy-0\""), output);
        assertEquals("legacy-0", sightingRepository.findById("legacy-1").orElseThrow().id());
        assertEquals(2, sightingRepository.findById("legacy-0").orElseThrow().imageUrls().size());
        assertEquals(1, speciesRepository.findById(species.id()).orElseThrow().referenceCount());
        assertThrows(tcc.aves.exception.DuplicateResourceException.class,
                () -> sightingRepository.delete("legacy-1"));
        var appended = sightingRepository.createIdempotent("post-migration", List.of(species.id()),
                LocalDate.of(2026, 9, 10), LocalTime.of(10, 15, 59), "https://synthetic.test/2");
        assertEquals("legacy-0", appended.id());
        assertEquals(3, appended.imageUrls().size());
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
                sightingIdempotencyTable(SIGHTINGS_TABLE),
                new SightingProperties(20, 50, 20, 60),
                new SightingCursorCodec(new ObjectMapper()));
    }
}
