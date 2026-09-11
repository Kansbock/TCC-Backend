package tcc.aves.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Emite e valida os JWTs da API.
 *
 * <p>O {@code subject} é o UUID imutável do usuário, não o e-mail — assim o
 * token continua válido mesmo que o usuário altere o próprio e-mail depois de
 * autenticado. O e-mail vai como claim informativa, não como identidade.
 *
 * <p>O segredo é obrigatório e validado no startup: sem ele, ou com menos de
 * 256 bits (mínimo recomendado para HS256), a aplicação falha ao subir em vez
 * de aceitar um valor fraco ou um default versionado no repositório.
 */
@Component
public class JwtUtil {

    private static final int MIN_SECRET_BYTES = 32; // 256 bits

    private final SecretKey key;
    private final long expiration;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration}") long expiration) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret não configurado. Defina a variável de ambiente JWT_SECRET " +
                    "com pelo menos " + MIN_SECRET_BYTES + " bytes (256 bits) antes de iniciar a aplicação.");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret é fraco demais: são necessários pelo menos " + MIN_SECRET_BYTES +
                    " bytes (256 bits) para assinatura HS256.");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.expiration = expiration;
    }

    public String generateToken(String userId, String email) {
        return Jwts.builder()
                .subject(userId)
                .claim("email", email)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(key)
                .compact();
    }

    public String extractUserId(String token) {
        return extractClaims(token).getSubject();
    }

    public boolean isTokenValid(String token) {
        try {
            extractClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
