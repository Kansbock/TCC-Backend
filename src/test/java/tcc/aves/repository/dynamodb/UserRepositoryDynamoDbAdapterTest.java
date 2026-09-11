package tcc.aves.repository.dynamodb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import tcc.aves.exception.DuplicateResourceException;
import tcc.aves.repository.UserRecord;
import tcc.aves.repository.dynamodb.model.EmailReservationItem;
import tcc.aves.repository.dynamodb.model.UserItem;
import tcc.aves.support.AbstractDynamoDbLocalTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@Disabled("Requires the amazon/dynamodb-local Docker image, which this environment cannot pull/run reliably.")
class UserRepositoryDynamoDbAdapterTest extends AbstractDynamoDbLocalTest {

    private static final String USERS_TABLE = "it-users-users";
    private static final String SPECIES_TABLE = "it-users-species";
    private static final String SIGHTINGS_TABLE = "it-users-sightings";

    private UserRepositoryDynamoDbAdapter userRepository;

    @BeforeAll
    void setUpClient() {
        initializeClients();
    }

    @BeforeEach
    void setUpRepository() {
        recreateTables(USERS_TABLE, SPECIES_TABLE, SIGHTINGS_TABLE);
        userRepository = new UserRepositoryDynamoDbAdapter(
                enhancedClient,
                userTable(USERS_TABLE),
                emailReservationTable(USERS_TABLE));
    }

    @Test
    void createPersistsUserAndEmailReservation() {
        UserRecord created = userRepository.create("Alice", " Alice@Example.com ", "hash-1");

        assertTrue(userRepository.findById(created.id()).isPresent());
        assertTrue(userRepository.findByEmail("alice@example.com").isPresent());

        Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> userItem = getItem(USERS_TABLE, UserItem.keyFor(created.id()));
        Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> reservation =
                getItem(USERS_TABLE, EmailReservationItem.keyFor("alice@example.com"));

        assertEquals(created.id(), userItem.get("id").s());
        assertEquals(" Alice@Example.com ", userItem.get("email").s());
        assertEquals(created.id(), reservation.get("userId").s());
        assertEquals(2, countItems(USERS_TABLE));
    }

    @Test
    void createRejectsNormalizedDuplicateEmailWithoutPartialWrites() {
        UserRecord original = userRepository.create("Alice", "alice@example.com", "hash-1");

        DuplicateResourceException exception = assertThrows(DuplicateResourceException.class,
                () -> userRepository.create("Bob", " Alice@Example.com ", "hash-2"));

        assertEquals("Email já cadastrado", exception.getMessage());
        assertEquals(2, countItems(USERS_TABLE));
        assertEquals(original.id(), userRepository.findByEmail("alice@example.com").orElseThrow().id());
    }

    @Test
    void updateSwapsEmailReservationAtomically() {
        UserRecord created = userRepository.create("Alice", "alice@example.com", "hash-1");

        UserRecord updated = userRepository.update(created.id(), "Alice Updated", "new@example.com");

        assertEquals("Alice Updated", updated.name());
        assertEquals("new@example.com", updated.email());
        assertTrue(getItem(USERS_TABLE, EmailReservationItem.keyFor("alice@example.com")).isEmpty());
        assertEquals(created.id(), getItem(USERS_TABLE, EmailReservationItem.keyFor("new@example.com")).get("userId").s());
        assertEquals("new@example.com", getItem(USERS_TABLE, UserItem.keyFor(created.id())).get("email").s());
    }

    @Test
    void deleteRemovesProfileAndReservation() {
        UserRecord created = userRepository.create("Alice", "alice@example.com", "hash-1");

        userRepository.delete(created.id());

        assertTrue(getItem(USERS_TABLE, UserItem.keyFor(created.id())).isEmpty());
        assertTrue(getItem(USERS_TABLE, EmailReservationItem.keyFor("alice@example.com")).isEmpty());
        assertEquals(0, countItems(USERS_TABLE));
    }
}
