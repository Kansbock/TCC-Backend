package tcc.aves.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import tcc.aves.dto.UserRequest;
import tcc.aves.dto.UserResponse;
import tcc.aves.dto.UserUpdateRequest;
import tcc.aves.exception.ResourceNotFoundException;
import tcc.aves.repository.UserRecord;
import tcc.aves.repository.UserRepository;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UserServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        userService = new UserService(userRepository, passwordEncoder);
    }

    @Test
    void createEncodesPasswordBeforeSaving() {
        UserRequest request = new UserRequest("Alice", "alice@example.com", "plain-secret");
        Instant now = Instant.parse("2026-09-10T12:30:00Z");

        when(userRepository.create(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                new UserRecord(
                        "user-1",
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        now,
                        now));

        UserResponse response = userService.create(request);

        ArgumentCaptor<String> passwordHashCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).create(eq("Alice"), eq("alice@example.com"), passwordHashCaptor.capture());
        String passwordHash = passwordHashCaptor.getValue();

        assertNotEquals("plain-secret", passwordHash);
        assertTrue(passwordEncoder.matches("plain-secret", passwordHash));
        assertEquals("user-1", response.id());
        assertEquals("Alice", response.name());
        assertEquals("alice@example.com", response.email());
    }

    @Test
    void findByIdReturnsMappedResponse() {
        Instant now = Instant.parse("2026-09-10T12:30:00Z");
        UserRecord record = new UserRecord("user-1", "Alice", "alice@example.com", "hash", now, now);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(record));

        UserResponse response = userService.findById("user-1");

        assertEquals("user-1", response.id());
        assertEquals("Alice", response.name());
        assertEquals("alice@example.com", response.email());
        assertEquals(now, response.createdAt());
        assertEquals(now, response.updatedAt());
    }

    @Test
    void findByIdThrowsWhenUserIsMissing() {
        when(userRepository.findById("missing")).thenReturn(Optional.empty());

        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> userService.findById("missing"));

        assertEquals("Usuário não encontrado", exception.getMessage());
    }

    @Test
    void updateReturnsMappedResponse() {
        Instant createdAt = Instant.parse("2026-09-01T08:00:00Z");
        Instant updatedAt = Instant.parse("2026-09-10T12:30:00Z");
        UserUpdateRequest request = new UserUpdateRequest("Alice Updated", "new@example.com");
        when(userRepository.update("user-1", "Alice Updated", "new@example.com")).thenReturn(
                new UserRecord("user-1", "Alice Updated", "new@example.com", "hash", createdAt, updatedAt));

        UserResponse response = userService.update("user-1", request);

        assertEquals("user-1", response.id());
        assertEquals("Alice Updated", response.name());
        assertEquals("new@example.com", response.email());
        assertEquals(createdAt, response.createdAt());
        assertEquals(updatedAt, response.updatedAt());
    }

    @Test
    void deleteDelegatesToRepository() {
        userService.delete("user-1");

        verify(userRepository).delete("user-1");
        verifyNoMoreInteractions(userRepository);
    }
}
