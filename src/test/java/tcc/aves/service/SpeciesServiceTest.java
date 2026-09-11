package tcc.aves.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tcc.aves.dto.SpeciesRequest;
import tcc.aves.dto.SpeciesResponse;
import tcc.aves.exception.ResourceNotFoundException;
import tcc.aves.repository.SpeciesRecord;
import tcc.aves.repository.SpeciesRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpeciesServiceTest {

    private SpeciesRepository speciesRepository;
    private SpeciesService speciesService;

    @BeforeEach
    void setUp() {
        speciesRepository = mock(SpeciesRepository.class);
        speciesService = new SpeciesService(speciesRepository);
    }

    @Test
    void createReturnsMappedResponse() {
        SpeciesRequest request = new SpeciesRequest("Arara", "Ara ararauna", "Desc", "Tips", "https://img");
        SpeciesRecord record = new SpeciesRecord(
                "species-1",
                "Arara",
                "Ara ararauna",
                "Desc",
                "Tips",
                "https://img",
                0,
                Instant.parse("2026-09-10T12:30:00Z"),
                Instant.parse("2026-09-10T12:30:00Z"));
        when(speciesRepository.create("Arara", "Ara ararauna", "Desc", "Tips", "https://img")).thenReturn(record);

        SpeciesResponse response = speciesService.create(request);

        assertEquals("species-1", response.id());
        assertEquals("Arara", response.name());
        assertEquals("Ara ararauna", response.scientificName());
        assertEquals("Desc", response.description());
        assertEquals("Tips", response.tips());
        assertEquals("https://img", response.imageUrl());
    }

    @Test
    void findAllReturnsMappedResponses() {
        when(speciesRepository.findAll()).thenReturn(List.of(
                speciesRecord("species-1", "Arara", "Ara ararauna"),
                speciesRecord("species-2", "Tucano", "Ramphastos toco")));

        List<SpeciesResponse> responses = speciesService.findAll();

        assertEquals(List.of("species-1", "species-2"), responses.stream().map(SpeciesResponse::id).toList());
        assertEquals(List.of("Arara", "Tucano"), responses.stream().map(SpeciesResponse::name).toList());
    }

    @Test
    void findByIdReturnsMappedResponse() {
        SpeciesRecord record = speciesRecord("species-1", "Arara", "Ara ararauna");
        when(speciesRepository.findById("species-1")).thenReturn(Optional.of(record));

        SpeciesResponse response = speciesService.findById("species-1");

        assertEquals("species-1", response.id());
        assertEquals("Arara", response.name());
    }

    @Test
    void findByIdThrowsWhenSpeciesIsMissing() {
        when(speciesRepository.findById("missing")).thenReturn(Optional.empty());

        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> speciesService.findById("missing"));

        assertEquals("Espécie não encontrada", exception.getMessage());
    }

    @Test
    void searchReturnsMappedResponses() {
        when(speciesRepository.search("ara")).thenReturn(List.of(speciesRecord("species-1", "Arara", "Ara ararauna")));

        List<SpeciesResponse> responses = speciesService.search("ara");

        assertEquals(1, responses.size());
        assertEquals("species-1", responses.getFirst().id());
        assertEquals("Arara", responses.getFirst().name());
    }

    @Test
    void updateReturnsMappedResponse() {
        SpeciesRequest request = new SpeciesRequest("Arara Azul", "Ara ararauna", "Nova", "Novas", "https://new");
        when(speciesRepository.update("species-1", "Arara Azul", "Ara ararauna", "Nova", "Novas", "https://new"))
                .thenReturn(new SpeciesRecord(
                        "species-1",
                        "Arara Azul",
                        "Ara ararauna",
                        "Nova",
                        "Novas",
                        "https://new",
                        0,
                        Instant.parse("2026-09-01T08:00:00Z"),
                        Instant.parse("2026-09-10T12:30:00Z")));

        SpeciesResponse response = speciesService.update("species-1", request);

        assertEquals("Arara Azul", response.name());
        assertEquals("Nova", response.description());
        assertEquals("Novas", response.tips());
    }

    @Test
    void deleteDelegatesToRepository() {
        speciesService.delete("species-1");

        verify(speciesRepository).delete("species-1");
        verifyNoMoreInteractions(speciesRepository);
    }

    private static SpeciesRecord speciesRecord(String id, String name, String scientificName) {
        Instant now = Instant.parse("2026-09-10T12:30:00Z");
        return new SpeciesRecord(id, name, scientificName, "Desc " + name, "Tips " + name, "https://img/" + id, 0, now, now);
    }
}
