package tcc.aves.repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Port de persistência de avistamentos. Avistamentos são globais — não há
 * conceito de proprietário em nenhum método aqui.
 */
public interface SightingRepository {

    /**
     * Cria um avistamento. Todas as espécies em {@code speciesIds} devem
     * existir; a implementação grava detalhes, referências por espécie e
     * incrementa {@code referenceCount} das espécies atomicamente. Lança
     * {@code ResourceNotFoundException} se alguma espécie não existir.
     */
    SightingRecord create(List<String> speciesIds, LocalDate date, LocalTime time, String imageUrl);

    Optional<SightingRecord> findById(String id);

    /**
     * Atualiza um avistamento, ajustando referências e contadores de espécie
     * conforme a diferença entre a lista antiga e a nova. Lança
     * {@code ResourceNotFoundException} se o avistamento ou alguma espécie
     * não existir, ou {@code ConcurrentUpdateException} em conflito de versão.
     */
    SightingRecord update(String id, List<String> speciesIds, LocalDate date, LocalTime time, String imageUrl);

    /** Lança {@code ResourceNotFoundException} se o avistamento não existir. */
    void delete(String id);

    /** Página de avistamentos do mais recente para o mais antigo, conforme os filtros informados. */
    SightingPage query(SightingQuery query);
}
