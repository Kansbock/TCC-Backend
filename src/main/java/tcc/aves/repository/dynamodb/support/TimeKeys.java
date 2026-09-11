package tcc.aves.repository.dynamodb.support;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Formata {@link Instant} em strings de largura fixa, seguras para
 * comparação lexicográfica em chaves do DynamoDB (sort key, GSI e cursor).
 *
 * <p>{@code Instant.toString()} não serve para isso: ele omite a parte
 * fracionária quando é zero e corta zeros à direita, então um instante em
 * ponto exato de segundo ({@code ...:00Z}) pode ordenar depois de um
 * instante fracionário do mesmo segundo ({@code ...:00.500Z}), porque
 * {@code '.'} (0x2E) é menor que {@code 'Z'} (0x5A) em ASCII. Usar
 * milissegundos de largura fixa elimina essa inconsistência.
 */
public final class TimeKeys {

    private static final DateTimeFormatter SORTABLE_INSTANT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter MONTH_BUCKET = DateTimeFormatter
            .ofPattern("yyyy-MM")
            .withZone(ZoneOffset.UTC);

    private TimeKeys() {
    }

    /** Instante formatado com milissegundos fixos, seguro para BETWEEN/ordenação em string. */
    public static String sortable(Instant instant) {
        return SORTABLE_INSTANT.format(instant);
    }

    /** Bucket mensal UTC (ex.: {@code 2026-09}) usado na GSI de timeline. */
    public static String monthBucket(Instant instant) {
        return MONTH_BUCKET.format(instant);
    }
}
