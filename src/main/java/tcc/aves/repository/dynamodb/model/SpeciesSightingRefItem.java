package tcc.aves.repository.dynamodb.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;
import tcc.aves.repository.dynamodb.support.TimeKeys;

import java.time.Instant;

/**
 * Item de referência espécie → avistamento na tabela {@code aves-sightings}.
 *
 * <p>Chave: {@code pk = SPECIES#<speciesId>}, {@code sk = <observedAt-ISO>#SIGHTING#<sightingId>}.
 * Permite consultar (via {@code Query}, nunca {@code Scan}) todos os
 * avistamentos de uma espécie ordenados por data/hora, sem duplicar os
 * detalhes completos — o chamador faz um batch-get de {@link SightingItem}
 * para montar a resposta final.
 */
@DynamoDbBean
public class SpeciesSightingRefItem {

    public static final String ITEM_TYPE = "SPECIES_SIGHTING_REF";
    public static final String PK_PREFIX = "SPECIES#";
    public static final String SK_SIGHTING_MARKER = "#SIGHTING#";

    private String pk;
    private String sk;
    private String itemType = ITEM_TYPE;
    private String speciesId;
    private String sightingId;
    private Instant observedAt;

    public static String pkFor(String speciesId) {
        return PK_PREFIX + speciesId;
    }

    public static String skFor(Instant observedAt, String sightingId) {
        return TimeKeys.sortable(observedAt) + SK_SIGHTING_MARKER + sightingId;
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

    public String getSpeciesId() {
        return speciesId;
    }

    public void setSpeciesId(String speciesId) {
        this.speciesId = speciesId;
    }

    public String getSightingId() {
        return sightingId;
    }

    public void setSightingId(String sightingId) {
        this.sightingId = sightingId;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(Instant observedAt) {
        this.observedAt = observedAt;
    }
}
