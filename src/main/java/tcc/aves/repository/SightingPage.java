package tcc.aves.repository;

import java.util.List;

public record SightingPage(
        List<SightingRecord> items,
        String nextCursor,
        boolean hasMore
) {
}
