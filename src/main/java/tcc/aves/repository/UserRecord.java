package tcc.aves.repository;

import java.time.Instant;
import tcc.aves.security.UserRole;

/**
 * Representação de usuário usada pela camada de aplicação, independente do
 * mecanismo de persistência. O adapter DynamoDB mapeia isso de/para
 * {@code UserItem}.
 */
public record UserRecord(
        String id,
        String name,
        String email,
        String passwordHash,
        Instant createdAt,
        Instant updatedAt,
        UserRole role
) {
    public UserRecord(String id, String name, String email, String passwordHash,
                      Instant createdAt, Instant updatedAt) {
        this(id, name, email, passwordHash, createdAt, updatedAt, UserRole.USER);
    }
}
