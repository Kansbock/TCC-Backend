package tcc.aves.repository.dynamodb.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;

import java.time.Instant;

/**
 * Item de perfil de espécie na tabela {@code aves-species}.
 *
 * <p>Chave: {@code pk = SPECIES#<uuid>}. {@code referenceCount} é
 * incrementado/decrementado transacionalmente sempre que um avistamento
 * passa a referenciar (ou deixa de referenciar) esta espécie, e é o que
 * impede a exclusão de espécies em uso (ver {@code ResourceInUseException}).
 */
@DynamoDbBean
public class SpeciesItem {

    public static final String ITEM_TYPE = "SPECIES";
    public static final String KEY_PREFIX = "SPECIES#";

    private String pk;
    private String itemType = ITEM_TYPE;
    private String id;
    private String commonName;
    private String scientificName;
    private String scientificNameNormalized;
    private String description;
    private String tips;
    private String imageUrl;
    private Long referenceCount = 0L;
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

    public String getCommonName() {
        return commonName;
    }

    public void setCommonName(String commonName) {
        this.commonName = commonName;
    }

    public String getScientificName() {
        return scientificName;
    }

    public void setScientificName(String scientificName) {
        this.scientificName = scientificName;
    }

    public String getScientificNameNormalized() {
        return scientificNameNormalized;
    }

    public void setScientificNameNormalized(String scientificNameNormalized) {
        this.scientificNameNormalized = scientificNameNormalized;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTips() {
        return tips;
    }

    public void setTips(String tips) {
        this.tips = tips;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public Long getReferenceCount() {
        return referenceCount;
    }

    public void setReferenceCount(Long referenceCount) {
        this.referenceCount = referenceCount;
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
