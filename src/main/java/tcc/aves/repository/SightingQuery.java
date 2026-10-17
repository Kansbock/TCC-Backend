package tcc.aves.repository;

import java.time.LocalDate;

/**
 * Parâmetros de consulta de avistamentos. {@code speciesId}, {@code from} e
 * {@code to} são opcionais e podem ser combinados; quando um de
 * {@code from}/{@code to} é informado, o outro também deve ser (validado na
 * camada HTTP, não aqui). {@code cursor} deve ser {@code null} na primeira
 * página e é sempre relativo à mesma combinação de filtros — trocar
 * {@code speciesId}/{@code from}/{@code to} entre chamadas com um cursor de
 * uma busca anterior não é suportado e deve ser tratado como {@code 400}.
 */
public record SightingQuery(
        String speciesId,
        LocalDate from,
        LocalDate to,
        String cursor,
        int limit,
        SightingSort sort
) {
    public SightingQuery(String speciesId, LocalDate from, LocalDate to, String cursor, int limit) {
        this(speciesId, from, to, cursor, limit, SightingSort.NEWEST);
    }
}
