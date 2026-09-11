package tcc.aves.dto;

import java.util.List;

/**
 * Envelope de paginação por cursor usado por listagens que não podem
 * retornar tudo de uma vez (avistamentos). {@code nextCursor} é opaco ao
 * cliente e só é válido para a mesma combinação de filtros da chamada que o
 * originou.
 */
public record CursorPage<T>(
        List<T> items,
        String nextCursor,
        boolean hasMore
) {
}
