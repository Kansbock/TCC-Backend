package tcc.aves.dto;

import java.time.Instant;

public record UserResponse(
        String id,
        String name,
        String email,
        Instant createdAt,
        Instant updatedAt
) {
}
