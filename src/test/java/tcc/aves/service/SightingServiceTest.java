package tcc.aves.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tcc.aves.config.SightingProperties;
import tcc.aves.dto.CursorPage;
import tcc.aves.dto.SightingRequest;
import tcc.aves.dto.SightingResponse;
import tcc.aves.dto.SpeciesResponse;
import tcc.aves.exception.ResourceNotFoundException;
import tcc.aves.repository.SightingPage;
import tcc.aves.repository.SightingQuery;
import tcc.aves.repository.SightingRecord;
import tcc.aves.repository.SightingRepository;
import tcc.aves.repository.SpeciesRecord;
import tcc.aves.repository.SpeciesRepository;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SightingServiceTest {

    private SightingRepository sightingRepository;
    private SpeciesRepository speciesRepository;
    private SightingProperties properties;
    private SightingService sightingService;

    @BeforeEach
    void setUp() {
        sightingRepository = mock(SightingRepository.class);
        speciesRepository = mock(SpeciesRepository.class);
        properties = new SightingProperties(2, 3, 2, 12);
        sightingService = new SightingService(sightingRepository, speciesRepository, properties);
    }

    @Test
    void createRejectsDuplicateSpeciesIds() {
        SightingRequest request = request(List.of("sp-1", "sp-1"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> sightingService.create(request));

        assertEquals("A lista de espécies não pode conter ids duplicados", exception.getMessage());
        verifyNoInteractions(sightingRepository, speciesRepository);
    }

    @Test
    void createRejectsTooManySpecies() {
        SightingRequest request = request(List.of("sp-1", "sp-2", "sp-3"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> sightingService.create(request));

        assertEquals("Um avistamento pode referenciar no máximo 2 espécies", exception.getMessage());
        verifyNoInteractions(sightingRepository, speciesRepository);
    }

    @Test
    void queryRejectsPartialDateRange() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> sightingService.query(null, LocalDate.of(2026, 9, 1), null, null, 1));

        assertEquals("Os filtros 'from' e 'to' devem ser informados juntos", exception.getMessage());
        verifyNoInteractions(sightingRepository);
    }

    @Test
    void queryRejectsInvalidDateRange() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> sightingService.query(null,
                        LocalDate.of(2026, 9, 2),
                        LocalDate.of(2026, 9, 1),
                        null,
                        1));

        assertEquals("O filtro 'from' deve ser anterior ou igual a 'to'", exception.getMessage());
        verifyNoInteractions(sightingRepository);
    }

    @Test
    void queryRejectsLimitOutsideConfiguredBounds() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> sightingService.query(null, null, null, null, 4));

        assertEquals("O parâmetro 'limit' deve estar entre 1 e 3", exception.getMessage());
        verifyNoInteractions(sightingRepository);
    }

    @Test
    void queryUsesDefaultLimitWhenOmitted() {
        when(sightingRepository.query(any())).thenReturn(new SightingPage(List.of(), null, false));

        sightingService.query(null, null, null, null, null);

        verify(sightingRepository).query(new SightingQuery(null, null, null, null, properties.defaultPageSize()));
    }

    @Test
    void findByIdThrowsWhenSightingIsMissing() {
        when(sightingRepository.findById("missing")).thenReturn(Optional.empty());

        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> sightingService.findById("missing"));

        assertEquals("Avistamento não encontrado", exception.getMessage());
    }

    @Test
    void queryHydratesFlatSpeciesListInRepositoryOrder() {
        SightingRecord first = sightingRecord("sig-1", List.of("sp-2", "sp-1"), LocalDate.of(2026, 9, 10), LocalTime.of(10, 15));
        when(sightingRepository.query(any())).thenReturn(new SightingPage(List.of(first), "next", true));
        when(speciesRepository.findAllByIds(any())).thenReturn(speciesMap(
                speciesRecord("sp-1", "Arara", "Ara ararauna"),
                speciesRecord("sp-2", "Tucano", "Ramphastos toco")));

        CursorPage<SightingResponse> page = sightingService.query("sp-2", null, null, null, 1);

        assertTrue(page.hasMore());
        assertEquals("next", page.nextCursor());
        assertEquals(1, page.items().size());
        List<SpeciesResponse> species = page.items().getFirst().species();
        assertEquals(List.of("sp-2", "sp-1"), species.stream().map(SpeciesResponse::id).toList());
        assertEquals(List.of("Tucano", "Arara"), species.stream().map(SpeciesResponse::name).toList());
    }

    @Test
    void findByIdThrowsWhenHydrationCannotResolveReferencedSpecies() {
        SightingRecord record = sightingRecord("sig-1", List.of("sp-1", "sp-2"), LocalDate.of(2026, 9, 10), LocalTime.NOON);
        when(sightingRepository.findById("sig-1")).thenReturn(Optional.of(record));
        when(speciesRepository.findAllByIds(List.of("sp-1", "sp-2"))).thenReturn(Map.of(
                "sp-1", speciesRecord("sp-1", "Arara", "Ara ararauna")));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> sightingService.findById("sig-1"));

        assertEquals("Inconsistência de dados: espécie referenciada não encontrada (id sp-2)", exception.getMessage());
    }

    @Test
    void createAndUpdateMapHydratedSpeciesResponses() {
        SightingRequest request = request(List.of("sp-1", "sp-2"));
        SightingRecord saved = sightingRecord("sig-1", List.of("sp-1", "sp-2"), request.date(), request.time());
        when(sightingRepository.create(request.speciesIds(), request.date(), request.time(), request.imageUrl())).thenReturn(saved);
        when(sightingRepository.update("sig-1", request.speciesIds(), request.date(), request.time(), request.imageUrl())).thenReturn(saved);
        when(speciesRepository.findAllByIds(List.of("sp-1", "sp-2"))).thenReturn(speciesMap(
                speciesRecord("sp-1", "Arara", "Ara ararauna"),
                speciesRecord("sp-2", "Tucano", "Ramphastos toco")));

        SightingResponse created = sightingService.create(request);
        SightingResponse updated = sightingService.update("sig-1", request);

        assertEquals(List.of("sp-1", "sp-2"), created.species().stream().map(SpeciesResponse::id).toList());
        assertEquals(List.of("sp-1", "sp-2"), updated.species().stream().map(SpeciesResponse::id).toList());
    }

    @Test
    void createWithIdempotencyKeyDelegatesToRepositoryIdempotentPath() {
        SightingRequest request = request(List.of("sp-1"));
        SightingRecord saved = sightingRecord("sig-1", List.of("sp-1"), request.date(), request.time());
        when(sightingRepository.createIdempotent("worker-key", request.speciesIds(), request.date(), request.time(), request.imageUrl()))
                .thenReturn(saved);
        when(speciesRepository.findAllByIds(List.of("sp-1"))).thenReturn(speciesMap(
                speciesRecord("sp-1", "Arara", "Ara ararauna")));

        SightingResponse created = sightingService.create(request, "worker-key");

        assertEquals(List.of("sp-1"), created.species().stream().map(SpeciesResponse::id).toList());
        verify(sightingRepository, never()).create(any(), any(), any(), any());
    }

    @Test
    void createWithoutIdempotencyKeyFallsBackToLegacyBehavior() {
        SightingRequest request = request(List.of("sp-1"));
        SightingRecord saved = sightingRecord("sig-1", List.of("sp-1"), request.date(), request.time());
        when(sightingRepository.create(request.speciesIds(), request.date(), request.time(), request.imageUrl())).thenReturn(saved);
        when(speciesRepository.findAllByIds(List.of("sp-1"))).thenReturn(speciesMap(
                speciesRecord("sp-1", "Arara", "Ara ararauna")));

        sightingService.create(request, null);
        sightingService.create(request, "  ");

        verify(sightingRepository, times(2)).create(request.speciesIds(), request.date(), request.time(), request.imageUrl());
        verify(sightingRepository, never()).createIdempotent(any(), any(), any(), any(), any());
    }

    @Test
    void deleteDelegatesToRepository() {
        sightingService.delete("sig-1");

        verify(sightingRepository).delete("sig-1");
        verifyNoMoreInteractions(sightingRepository);
    }

    @Test
    void publicApiHasNoOwnerOrUserScopedParameters() {
        for (Method method : SightingService.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            for (Parameter parameter : method.getParameters()) {
                String typeName = parameter.getType().getSimpleName().toLowerCase();
                assertFalse(typeName.contains("user"), () -> method.getName() + " should not accept user-scoped parameters");
                assertFalse(typeName.contains("owner"), () -> method.getName() + " should not accept owner-scoped parameters");
            }
        }
    }

    private static Map<String, SpeciesRecord> speciesMap(SpeciesRecord... records) {
        Map<String, SpeciesRecord> map = new LinkedHashMap<>();
        for (SpeciesRecord record : records) {
            map.put(record.id(), record);
        }
        return map;
    }

    private static SpeciesRecord speciesRecord(String id, String name, String scientificName) {
        Instant now = Instant.parse("2026-09-10T12:30:00Z");
        return new SpeciesRecord(id, name, scientificName, "Desc " + name, "Tips " + name, "https://img/" + id, 0, now, now);
    }

    private static SightingRecord sightingRecord(String id, List<String> speciesIds, LocalDate date, LocalTime time) {
        Instant now = Instant.parse("2026-09-10T12:30:00Z");
        return new SightingRecord(id, speciesIds, date, time, "https://img/" + id, now, now);
    }

    private static SightingRequest request(List<String> speciesIds) {
        return new SightingRequest(speciesIds, LocalDate.of(2026, 9, 10), LocalTime.of(10, 15), "https://img/sighting");
    }
}
