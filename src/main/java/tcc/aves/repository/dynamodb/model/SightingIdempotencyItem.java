package tcc.aves.repository.dynamodb.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.time.Instant;

/**
 * Item de idempotência de criação de avistamento na tabela {@code aves-sightings}.
 *
 * <p>Chave: {@code pk = IDEMPOTENCY#<chave-informada-pelo-cliente>},
 * {@code sk = IDEMPOTENCY} (fixo). Guarda a impressão digital do payload
 * original e o id do avistamento criado, para que uma repetição com a mesma
 * chave devolva o avistamento original quando o payload for idêntico, ou
 * seja rejeitada com conflito quando o payload divergir. Escrito na mesma
 * transação que {@link SightingItem}, {@link SpeciesSightingRefItem} e as
 * atualizações de {@code referenceCount}, condicionado a não existir ainda —
 * o mesmo padrão usado por {@link ScientificNameReservationItem}.
 */
@DynamoDbBean
public class SightingIdempotencyItem {

    public static final String ITEM_TYPE = "SIGHTING_IDEMPOTENCY";
    public static final String PK_PREFIX = "IDEMPOTENCY#";
    public static final String SORT_KEY = "IDEMPOTENCY";

    private String pk;
    private String sk = SORT_KEY;
    private String itemType = ITEM_TYPE;
    private String idempotencyKey;
    private String requestFingerprint;
    private String sightingId;
    private Instant createdAt;

    public static String keyFor(String idempotencyKey) {
        return PK_PREFIX + idempotencyKey;
    }

    @DynamoDbPartitionKey
    public String getPk() {
        return pk;
    }

    public void setPk(String pk) {
        this.pk = pk;
    }

    @DynamoDbSortKey
    public String getSk() {
        return sk;
    }

    public void setSk(String sk) {
        this.sk = sk;
    }

    public String getItemType() {
        return itemType;
    }

    public void setItemType(String itemType) {
        this.itemType = itemType;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public void setRequestFingerprint(String requestFingerprint) {
        this.requestFingerprint = requestFingerprint;
    }

    public String getSightingId() {
        return sightingId;
    }

    public void setSightingId(String sightingId) {
        this.sightingId = sightingId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
