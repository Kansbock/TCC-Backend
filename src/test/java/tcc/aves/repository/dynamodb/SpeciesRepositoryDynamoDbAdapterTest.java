package tcc.aves.repository.dynamodb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import tcc.aves.config.SightingProperties;
import tcc.aves.exception.DuplicateResourceException;
import tcc.aves.exception.ResourceInUseException;
import tcc.aves.repository.SpeciesRecord;
import tcc.aves.repository.SightingRecord;
import tcc.aves.repository.dynamodb.model.ScientificNameReservationItem;
import tcc.aves.repository.dynamodb.model.SpeciesItem;
import tcc.aves.repository.dynamodb.support.SightingCursorCodec;
import tcc.aves.support.AbstractDynamoDbLocalTest;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@Disabled("Requires the amazon/dynamodb-local Docker image, which this environment cannot pull/run reliably.")
class SpeciesRepositoryDynamoDbAdapterTest extends AbstractDynamoDbLocalTest {

    private static final String USERS_TABLE = "it-species-users";
    private static final String SPECIES_TABLE = "it-species-species";
    private static final String SIGHTINGS_TABLE = "it-species-sightings";

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
        sightingRepository = new SightingRepositoryDynamoDbAdapter(
                enhancedClient,
                sightingTable(SIGHTINGS_TABLE),
                speciesSightingRefTable(SIGHTINGS_TABLE),
                sightingTimelineIndex(SIGHTINGS_TABLE),
                speciesTable(SPECIES_TABLE),
                sightingIdempotencyTable(SIGHTINGS_TABLE),
                new SightingProperties(20, 50, 20, 60),
                new SightingCursorCodec(new ObjectMapper()));
    }

    @Test
    void createPersistsSpeciesAndScientificReservationWithZeroReferenceCount() {
        SpeciesRecord created = speciesRepository.create("Arara", "Ara ararauna", "Desc", "Tips", "https://img");

        Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> speciesItem = getItem(SPECIES_TABLE, SpeciesItem.keyFor(created.id()));
        Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> reservation =
                getItem(SPECIES_TABLE, ScientificNameReservationItem.keyFor("ara ararauna"));

        assertEquals(created.id(), speciesItem.get("id").s());
        assertEquals("0", speciesItem.get("referenceCount").n());
        assertEquals(created.id(), reservation.get("speciesId").s());
        assertEquals(2, countItems(SPECIES_TABLE));
    }

    @Test
    void createRejectsNormalizedDuplicateScientificNameWithoutPartialWrites() {
        SpeciesRecord original = speciesRepository.create("Arara", "Ara ararauna", "Desc", "Tips", "https://img");

        DuplicateResourceException exception = assertThrows(DuplicateResourceException.class,
                () -> speciesRepository.create("Outra", "  ARA ARARAUNA ", "Desc", "Tips", "https://img2"));

        assertEquals("Nome científico já cadastrado", exception.getMessage());
        assertEquals(2, countItems(SPECIES_TABLE));
        assertEquals(original.id(), getItem(SPECIES_TABLE, ScientificNameReservationItem.keyFor("ara ararauna")).get("speciesId").s());
    }

    @Test
    void updateSwapsScientificNameReservationAtomically() {
        SpeciesRecord created = speciesRepository.create("Arara", "Ara ararauna", "Desc", "Tips", "https://img");

        SpeciesRecord updated = speciesRepository.update(created.id(), "Arara Azul", "Ara glaucogularis", "Nova", "Novas", "https://new");

        assertEquals("Arara Azul", updated.name());
        assertEquals("Ara glaucogularis", updated.scientificName());
        assertTrue(getItem(SPECIES_TABLE, ScientificNameReservationItem.keyFor("ara ararauna")).isEmpty());
        assertEquals(created.id(), getItem(SPECIES_TABLE, ScientificNameReservationItem.keyFor("ara glaucogularis")).get("speciesId").s());
        assertEquals("Ara glaucogularis", getItem(SPECIES_TABLE, SpeciesItem.keyFor(created.id())).get("scientificName").s());
    }

    @Test
    void deleteRejectsReferencedSpeciesAndSucceedsAfterReferenceRemoved() {
        SpeciesRecord species = speciesRepository.create("Arara", "Ara ararauna", "Desc", "Tips", "https://img");
        SightingRecord sighting = sightingRepository.create(List.of(species.id()), LocalDate.of(2026, 9, 10), LocalTime.of(10, 15), "https://sighting");

        ResourceInUseException exception = assertThrows(ResourceInUseException.class,
                () -> speciesRepository.delete(species.id()));

        assertEquals("Espécie não pode ser removida: está referenciada por avistamentos existentes", exception.getMessage());
        assertEquals(1L, speciesRepository.findById(species.id()).orElseThrow().referenceCount());

        sightingRepository.delete(sighting.id());
        speciesRepository.delete(species.id());

        assertTrue(getItem(SPECIES_TABLE, SpeciesItem.keyFor(species.id())).isEmpty());
        assertTrue(getItem(SPECIES_TABLE, ScientificNameReservationItem.keyFor("ara ararauna")).isEmpty());
    }
}
