package tcc.aves.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Corpo padrão de toda resposta de erro da API.
 *
 * <p>{@code fieldErrors} só é preenchido em falhas de validação; nos outros
 * casos fica nulo e é omitido do JSON (ver
 * {@code spring.jackson.default-property-inclusion=non_null}).
 */
public record ApiError(
        int status,
        String error,
        String message,
        Map<String, String> fieldErrors,
        String path,
        LocalDateTime timestamp
) {

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(status, error, message, null, path, LocalDateTime.now());
    }

    public static ApiError ofFields(int status,
                                    String error,
                                    String message,
                                    Map<String, String> fieldErrors,
                                    String path) {
        return new ApiError(status, error, message, fieldErrors, path, LocalDateTime.now());
    }
}
