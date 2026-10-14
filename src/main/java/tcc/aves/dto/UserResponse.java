package tcc.aves.dto;

import java.time.Instant;
import tcc.aves.security.UserRole;

public record UserResponse(
        String id,
        String name,
        String email,
        Instant createdAt,
        Instant updatedAt,
        UserRole role
) {
    public UserResponse(String id, String name, String email, Instant createdAt, Instant updatedAt) {
        this(id, name, email, createdAt, updatedAt, UserRole.USER);
    }
}
