package tcc.aves.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private static final String VALID_SECRET = "test-only-secret-key-not-for-production-use-1234567890";

    @Test
    void constructorRejectsBlankSecret() {
        assertThrows(IllegalStateException.class, () -> new JwtUtil(" ", 1_000L));
        assertThrows(IllegalStateException.class, () -> new JwtUtil(null, 1_000L));
    }

    @Test
    void constructorRejectsShortSecret() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new JwtUtil("short-secret", 1_000L));

        assertTrue(exception.getMessage().contains("32 bytes"));
    }

    @Test
    void tokenRoundTripsAndValidates() {
        JwtUtil jwtUtil = new JwtUtil(VALID_SECRET, 60_000L);

        String token = jwtUtil.generateToken("user-1", "user@example.com");

        assertEquals("user-1", jwtUtil.extractUserId(token));
        assertTrue(jwtUtil.isTokenValid(token));
        assertFalse(jwtUtil.isTokenValid("not-a-jwt"));
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        JwtUtil signer = new JwtUtil(VALID_SECRET, 60_000L);
        JwtUtil verifier = new JwtUtil("another-test-secret-key-not-for-production-abcdef", 60_000L);

        String token = signer.generateToken("user-1", "user@example.com");

        assertFalse(verifier.isTokenValid(token));
    }
}
