package tcc.aves.repository.dynamodb.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/**
 * Item de reserva de unicidade de nome científico na tabela {@code aves-species}.
 *
 * <p>Chave: {@code pk = SCIENTIFIC#<nome-cientifico-normalizado>}.
 */
@DynamoDbBean
public class ScientificNameReservationItem {

    public static final String ITEM_TYPE = "SCIENTIFIC_UNIQUE";
    public static final String KEY_PREFIX = "SCIENTIFIC#";

    private String pk;
    private String itemType = ITEM_TYPE;
    private String speciesId;

    public static String keyFor(String scientificNameNormalized) {
        return KEY_PREFIX + scientificNameNormalized;
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

    public String getSpeciesId() {
        return speciesId;
    }

    public void setSpeciesId(String speciesId) {
        this.speciesId = speciesId;
    }
}
