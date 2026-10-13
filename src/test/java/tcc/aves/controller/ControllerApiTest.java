package tcc.aves.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tcc.aves.dto.SightingResponse;
import tcc.aves.dto.SpeciesResponse;
import tcc.aves.exception.DuplicateResourceException;
import tcc.aves.exception.ResourceNotFoundException;
import tcc.aves.exception.ServiceUnavailableException;
import tcc.aves.repository.UserRecord;
import tcc.aves.repository.UserRepository;
import tcc.aves.security.JwtUtil;
import tcc.aves.service.SightingService;
import tcc.aves.service.SpeciesService;
import tcc.aves.service.UserService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@TestPropertySource(properties = "jwt.secret=test-only-secret-key-not-for-production-use-1234567890")
class ControllerApiTest {

    private static final String SELF_ID = "user-self";
    private static final String OTHER_ID = "user-other";
    private static final String EMAIL = "self@example.com";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserService userService;

    @Autowired
    private SpeciesService speciesService;

    @Autowired
    private SightingService sightingService;

    @Autowired
    private UserRepository userRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        reset(userService, speciesService, sightingService, userRepository);
        when(userRepository.findById(SELF_ID)).thenReturn(Optional.of(userRecord(SELF_ID, EMAIL)));
        when(userRepository.findById(OTHER_ID)).thenReturn(Optional.of(userRecord(OTHER_ID, "other@example.com")));
    }

    @Test
    void protectedEndpointWithoutTokenReturnsUnauthorizedApiError() throws Exception {
        mockMvc.perform(get("/api/users/{id}", SELF_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Não autenticado"))
                .andExpect(jsonPath("$.path").value("/api/users/" + SELF_ID))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void protectedEndpointWithInvalidTokenReturnsUnauthorizedApiError() throws Exception {
        mockMvc.perform(get("/api/sightings/{id}", "sig-1")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Não autenticado"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void userCanOnlyAccessOwnProfile() throws Exception {
        mockMvc.perform(get("/api/users/{id}", OTHER_ID)
                        .header("Authorization", bearer(SELF_ID, EMAIL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());

        verify(userService, never()).findById(anyString());
    }

    @Test
    void publicSpeciesEndpointWorksWithoutToken() throws Exception {
        when(speciesService.findAll()).thenReturn(List.of(speciesResponse("sp-1", "Arara", "Ara ararauna")));

        mockMvc.perform(get("/api/species"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("sp-1"))
                .andExpect(jsonPath("$[0].name").value("Arara"));
    }

    @Test
    void healthEndpointWorksWithoutTokenAndDoesNotUseServices() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verifyNoInteractions(userService, speciesService, sightingService, userRepository);
    }

    @Test
    void missingResourceReturnsNotFoundApiError() throws Exception {
        when(userService.findById(SELF_ID)).thenThrow(new ResourceNotFoundException("Usuário não encontrado"));

        mockMvc.perform(get("/api/users/{id}", SELF_ID)
                        .header("Authorization", bearer(SELF_ID, EMAIL)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Usuário não encontrado"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void duplicateResourceReturnsConflictApiError() throws Exception {
        when(userService.create(any())).thenThrow(new DuplicateResourceException("Email já cadastrado"));

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Alice",
                                  "email": "alice@example.com",
                                  "password": "secret1"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Email já cadastrado"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void serviceUnavailableReturnsServiceUnavailableApiError() throws Exception {
        when(sightingService.query(null, null, null, null, null, false, null))
                .thenThrow(new ServiceUnavailableException("Dynamo down"));

        mockMvc.perform(get("/api/sightings")
                        .header("Authorization", bearer(SELF_ID, EMAIL)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value("Serviço temporariamente indisponível, tente novamente"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void invalidRequestBodyReturnsValidationApiErrorWithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "",
                                  "email": "not-an-email",
                                  "password": "123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Falha na validação dos campos enviados"))
                .andExpect(jsonPath("$.fieldErrors.name").value("Nome é obrigatório"))
                .andExpect(jsonPath("$.fieldErrors.email").value("Email deve ser válido"))
                .andExpect(jsonPath("$.fieldErrors.password").value("Senha deve ter entre 6 e 72 caracteres"));
    }

    @Test
    void validTokenAllowsAuthenticatedControllerFlow() throws Exception {
        SightingResponse response = new SightingResponse(
                "sig-1",
                List.of(speciesResponse("sp-1", "Arara", "Ara ararauna")),
                LocalDate.of(2026, 9, 10),
                LocalTime.of(10, 15),
                "https://img/sig-1",
                Instant.parse("2026-09-10T12:30:00Z"),
                Instant.parse("2026-09-10T12:30:00Z"));
        when(sightingService.findById("sig-1")).thenReturn(response);

        mockMvc.perform(get("/api/sightings/{id}", "sig-1")
                        .header("Authorization", bearer(SELF_ID, EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("sig-1"))
                .andExpect(jsonPath("$.species", hasSize(1)))
                .andExpect(jsonPath("$.species[0].id").value("sp-1"));
    }

    @Test
    void createSightingWithoutIdempotencyKeyUsesLegacyServiceMethod() throws Exception {
        SightingResponse response = sightingResponse();
        when(sightingService.create(any())).thenReturn(response);

        mockMvc.perform(post("/api/sightings")
                        .header("Authorization", bearer(SELF_ID, EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sightingRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("sig-1"));

        verify(sightingService).create(any());
        verify(sightingService, never()).create(any(), anyString());
    }

    @Test
    void createSightingWithIdempotencyKeyHeaderUsesIdempotentServiceMethod() throws Exception {
        SightingResponse response = sightingResponse();
        when(sightingService.create(any(), eq("worker-key-123"))).thenReturn(response);

        mockMvc.perform(post("/api/sightings")
                        .header("Authorization", bearer(SELF_ID, EMAIL))
                        .header("Idempotency-Key", "worker-key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sightingRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("sig-1"));

        verify(sightingService).create(any(), eq("worker-key-123"));
        verify(sightingService, never()).create(any());
    }

    @Test
    void createSightingReusedIdempotencyKeyWithDifferentPayloadReturnsConflict() throws Exception {
        when(sightingService.create(any(), eq("worker-key-456")))
                .thenThrow(new DuplicateResourceException("Idempotency-Key já foi usada com uma requisição diferente"));

        mockMvc.perform(post("/api/sightings")
                        .header("Authorization", bearer(SELF_ID, EMAIL))
                        .header("Idempotency-Key", "worker-key-456")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sightingRequestJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Idempotency-Key já foi usada com uma requisição diferente"));
    }

    private static SightingResponse sightingResponse() {
        Instant now = Instant.parse("2026-09-10T12:30:00Z");
        return new SightingResponse(
                "sig-1",
                List.of(speciesResponse("sp-1", "Arara", "Ara ararauna")),
                LocalDate.of(2026, 9, 10),
                LocalTime.of(10, 15),
                "https://img/sig-1",
                now,
                now);
    }

    private static String sightingRequestJson() {
        return """
                {
                  "speciesIds": ["sp-1"],
                  "date": "2026-09-10",
                  "time": "10:15:00",
                  "imageUrl": "https://img/sig-1"
                }
                """;
    }

    @Test
    void maintenanceBlocksSightingWritesButKeepsReadsAvailable() throws Exception {
        SightingController controller = webApplicationContext.getBean(SightingController.class);
        Object previous = org.springframework.test.util.ReflectionTestUtils.getField(controller, "writesPaused");
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "writesPaused", true);
        String body = """
                {"speciesIds":["synthetic-species"],"date":"2026-09-10","time":"10:15:00","imageUrl":"https://synthetic.test/photo"}
                """;
        try {
            mockMvc.perform(post("/api/sightings").header("Authorization", bearer(SELF_ID, EMAIL))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value(503));
            mockMvc.perform(put("/api/sightings/sig-1").header("Authorization", bearer(SELF_ID, EMAIL))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isServiceUnavailable());
            mockMvc.perform(delete("/api/sightings/sig-1").header("Authorization", bearer(SELF_ID, EMAIL)))
                    .andExpect(status().isServiceUnavailable());
            verifyNoInteractions(sightingService);
            Instant now = Instant.parse("2026-09-10T10:15:00Z");
            when(sightingService.findById("sig-1")).thenReturn(new SightingResponse(
                    "sig-1", List.of(), LocalDate.of(2026, 9, 10), LocalTime.of(10, 15), null, now, now));
            mockMvc.perform(get("/api/sightings/sig-1").header("Authorization", bearer(SELF_ID, EMAIL)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("sig-1"));
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(controller, "writesPaused", previous);
        }
    }

    private String bearer(String userId, String email) {
        return "Bearer " + jwtUtil.generateToken(userId, email);
    }

    private static UserRecord userRecord(String id, String email) {
        Instant now = Instant.parse("2026-09-10T12:30:00Z");
        return new UserRecord(id, "User " + id, email, "hash", now, now);
    }

    private static SpeciesResponse speciesResponse(String id, String name, String scientificName) {
        Instant now = Instant.parse("2026-09-10T12:30:00Z");
        return new SpeciesResponse(id, name, scientificName, "Desc", "Tips", "https://img/" + id, now, now);
    }

    @TestConfiguration
    static class MockBeansConfig {
        @Bean
        @Primary
        UserService userService() {
            return Mockito.mock(UserService.class);
        }

        @Bean
        @Primary
        SpeciesService speciesService() {
            return Mockito.mock(SpeciesService.class);
        }

        @Bean
        @Primary
        SightingService sightingService() {
            return Mockito.mock(SightingService.class);
        }

        @Bean
        @Primary
        UserRepository userRepository() {
            return Mockito.mock(UserRepository.class);
        }
    }
}
