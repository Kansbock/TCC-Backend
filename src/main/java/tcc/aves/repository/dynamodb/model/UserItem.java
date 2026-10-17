package tcc.aves.repository.dynamodb.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;

import java.time.Instant;

/**
 * Item de perfil do usuário na tabela {@code aves-users}.
 *
 * <p>Chave: {@code pk = USER#<uuid>}. A mesma tabela guarda também
 * {@link EmailReservationItem}, usado apenas para garantir e-mail único —
 * ambos os itens são gravados/atualizados juntos em uma transação.
 */
@DynamoDbBean
public class UserItem {

    public static final String ITEM_TYPE = "USER";
    public static final String KEY_PREFIX = "USER#";

    private String pk;
    private String itemType = ITEM_TYPE;
    private String id;
    private String name;
    private String email;
    private String emailNormalized;
    private String passwordHash;
    private String role = "USER";
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;
    private Instant notificationActivityAt;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getEmailNormalized() {
        return emailNormalized;
    }

    public void setEmailNormalized(String emailNormalized) {
        this.emailNormalized = emailNormalized;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
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

    public Instant getNotificationActivityAt() { return notificationActivityAt; }
    public void setNotificationActivityAt(Instant value) { notificationActivityAt = value; }
}
