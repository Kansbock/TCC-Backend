package tcc.aves.security;

import java.util.Locale;

public enum UserRole {
    USER,
    ADMIN;

    public static UserRole fromStoredValue(String value) {
        if (value == null || value.isBlank()) {
            return USER;
        }
        return UserRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
