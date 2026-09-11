package tcc.aves.repository.dynamodb.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/**
 * Item de reserva de unicidade de e-mail na tabela {@code aves-users}.
 *
 * <p>Chave: {@code pk = EMAIL#<email-normalizado>}. Existir esse item
 * significa que o e-mail já está em uso pelo usuário referenciado em
 * {@code userId}. Criado/removido sempre na mesma transação que o
 * {@link UserItem} correspondente.
 */
@DynamoDbBean
public class EmailReservationItem {

    public static final String ITEM_TYPE = "EMAIL_UNIQUE";
    public static final String KEY_PREFIX = "EMAIL#";

    private String pk;
    private String itemType = ITEM_TYPE;
    private String userId;

    public static String keyFor(String emailNormalized) {
        return KEY_PREFIX + emailNormalized;
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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
