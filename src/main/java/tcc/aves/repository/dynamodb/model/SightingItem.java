package tcc.aves.repository.dynamodb.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;
import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Item de detalhes do avistamento na tabela {@code aves-sightings}.
 *
 * <p>Chave primária: {@code pk = SIGHTING#<uuid>}, {@code sk = DETAILS}.
 * A mesma tabela guarda um {@link SpeciesSightingRefItem} por espécie
 * referenciada, para permitir consulta eficiente por espécie via {@code Query}.
 *
 * <p>{@code timelinePk}/{@code timelineSk} alimentam a GSI
 * {@value #TIMELINE_INDEX_NAME} (bucket mensal), usada para consultas por
 * intervalo de datas sem {@code Scan}. O nome do índice é fixo em código
 * porque a anotação do SDK exige uma constante em tempo de compilação — se
 * a propriedade {@code app.dynamodb.sightings-timeline-index} for alterada,
 * esta constante deve ser atualizada e a aplicação recompilada.
 */
@DynamoDbBean
public class SightingItem {

    public static final String ITEM_TYPE = "SIGHTING_DETAILS";
    public static final String KEY_PREFIX = "SIGHTING#";
    public static final String DETAILS_SORT_KEY = "DETAILS";
    public static final String TIMELINE_INDEX_NAME = "timeline-index";
    public static final String PRIMARY_SPECIES_INDEX_NAME = "primary-species-index";
    public static final String TIMELINE_PK_PREFIX = "SIGHTINGS#";
    public static final String PRIMARY_SPECIES_PK = "SIGHTINGS#PRIMARY_SPECIES";

    private String pk;
    private String sk = DETAILS_SORT_KEY;
    private String itemType = ITEM_TYPE;
    private String id;
    private LocalDate date;
    private LocalTime time;
    private String imageUrl;
    private List<String> speciesIds;
    private Instant observedAt;
    private String timelinePk;
    private String timelineSk;
    private String primarySpeciesPk;
    private String primarySpeciesSk;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;

    public static String keyFor(String id) {
        return KEY_PREFIX + id;
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

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public LocalTime getTime() {
        return time;
    }

    public void setTime(LocalTime time) {
        this.time = time;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public List<String> getSpeciesIds() {
        return speciesIds;
    }

    public void setSpeciesIds(List<String> speciesIds) {
        this.speciesIds = speciesIds;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(Instant observedAt) {
        this.observedAt = observedAt;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = TIMELINE_INDEX_NAME)
    public String getTimelinePk() {
        return timelinePk;
    }

    public void setTimelinePk(String timelinePk) {
        this.timelinePk = timelinePk;
    }

    @DynamoDbSecondarySortKey(indexNames = TIMELINE_INDEX_NAME)
    public String getTimelineSk() {
        return timelineSk;
    }

    public void setTimelineSk(String timelineSk) {
        this.timelineSk = timelineSk;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = PRIMARY_SPECIES_INDEX_NAME)
    public String getPrimarySpeciesPk() {
        return primarySpeciesPk;
    }

    public void setPrimarySpeciesPk(String primarySpeciesPk) {
        this.primarySpeciesPk = primarySpeciesPk;
    }

    @DynamoDbSecondarySortKey(indexNames = PRIMARY_SPECIES_INDEX_NAME)
    public String getPrimarySpeciesSk() {
        return primarySpeciesSk;
    }

    public void setPrimarySpeciesSk(String primarySpeciesSk) {
        this.primarySpeciesSk = primarySpeciesSk;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @DynamoDbVersionAttribute
    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
