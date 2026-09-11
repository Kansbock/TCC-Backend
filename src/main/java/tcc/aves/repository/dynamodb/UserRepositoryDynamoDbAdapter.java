package tcc.aves.repository.dynamodb;

import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import tcc.aves.exception.ConcurrentUpdateException;
import tcc.aves.exception.DuplicateResourceException;
import tcc.aves.exception.ResourceNotFoundException;
import tcc.aves.repository.UserRecord;
import tcc.aves.repository.UserRepository;
import tcc.aves.repository.dynamodb.model.EmailReservationItem;
import tcc.aves.repository.dynamodb.model.UserItem;
import tcc.aves.repository.dynamodb.support.DynamoDbSupport;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementação DynamoDB do port {@link UserRepository}.
 *
 * <p>Perfil e reserva de e-mail vivem na mesma tabela ({@code aves-users}) e
 * são sempre criados/alterados/removidos juntos em uma transação, para que
 * nunca exista um perfil sem reserva (ou vice-versa) — condição necessária
 * para a unicidade de e-mail sem uma constraint relacional.
 */
@Repository
public class UserRepositoryDynamoDbAdapter implements UserRepository {

    private static final Expression NOT_EXISTS = Expression.builder()
            .expression("attribute_not_exists(pk)")
            .build();

    private final DynamoDbEnhancedClient enhancedClient;
    private final DynamoDbTable<UserItem> userTable;
    private final DynamoDbTable<EmailReservationItem> emailReservationTable;

    public UserRepositoryDynamoDbAdapter(
            DynamoDbEnhancedClient enhancedClient,
            DynamoDbTable<UserItem> userTable,
            DynamoDbTable<EmailReservationItem> emailReservationTable) {
        this.enhancedClient = enhancedClient;
        this.userTable = userTable;
        this.emailReservationTable = emailReservationTable;
    }

    @Override
    public UserRecord create(String name, String email, String passwordHash) {
        String id = UUID.randomUUID().toString();
        String normalized = normalize(email);
        Instant now = Instant.now();

        UserItem user = new UserItem();
        user.setPk(UserItem.keyFor(id));
        user.setId(id);
        user.setName(name);
        user.setEmail(email);
        user.setEmailNormalized(normalized);
        user.setPasswordHash(passwordHash);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        EmailReservationItem reservation = new EmailReservationItem();
        reservation.setPk(EmailReservationItem.keyFor(normalized));
        reservation.setUserId(id);

        try {
            enhancedClient.transactWriteItems(TransactWriteItemsEnhancedRequest.builder()
                    .addPutItem(emailReservationTable, TransactPutItemEnhancedRequest
                            .builder(EmailReservationItem.class)
                            .item(reservation)
                            .conditionExpression(NOT_EXISTS)
                            .build())
                    .addPutItem(userTable, TransactPutItemEnhancedRequest
                            .builder(UserItem.class)
                            .item(user)
                            .conditionExpression(NOT_EXISTS)
                            .build())
                    .build());
        } catch (TransactionCanceledException e) {
            if (DynamoDbSupport.isConditionFailure(e.cancellationReasons(), 0)) {
                throw new DuplicateResourceException("Email já cadastrado");
            }
            RuntimeException translated = DynamoDbSupport.translateGeneric(e);
            throw translated != null ? translated : e;
        } catch (RuntimeException e) {
            RuntimeException translated = DynamoDbSupport.translateGeneric(e);
            throw translated != null ? translated : e;
        }

        return toRecord(user);
    }

    @Override
    public Optional<UserRecord> findById(String id) {
        UserItem item = userTable.getItem(GetItemEnhancedRequest.builder()
                .key(Key.builder().partitionValue(UserItem.keyFor(id)).build())
                .consistentRead(true)
                .build());
        return Optional.ofNullable(item).map(this::toRecord);
    }

    @Override
    public Optional<UserRecord> findByEmail(String email) {
        String normalized = normalize(email);
        EmailReservationItem reservation = emailReservationTable.getItem(GetItemEnhancedRequest.builder()
                .key(Key.builder().partitionValue(EmailReservationItem.keyFor(normalized)).build())
                .consistentRead(true)
                .build());
        if (reservation == null) {
            return Optional.empty();
        }
        return findById(reservation.getUserId());
    }

    @Override
    public UserRecord update(String id, String name, String email) {
        UserItem current = userTable.getItem(GetItemEnhancedRequest.builder()
                .key(Key.builder().partitionValue(UserItem.keyFor(id)).build())
                .consistentRead(true)
                .build());
        if (current == null) {
            throw new ResourceNotFoundException("Usuário não encontrado");
        }

        String oldNormalized = current.getEmailNormalized();
        String newNormalized = normalize(email);
        boolean emailChanged = !newNormalized.equals(oldNormalized);

        current.setName(name);
        current.setEmail(email);
        current.setEmailNormalized(newNormalized);
        current.setUpdatedAt(Instant.now());

        if (!emailChanged) {
            try {
                userTable.updateItem(current);
            } catch (RuntimeException e) {
                RuntimeException translated = DynamoDbSupport.translateGeneric(e);
                throw translated != null ? translated : e;
            }
            return toRecord(current);
        }

        EmailReservationItem newReservation = new EmailReservationItem();
        newReservation.setPk(EmailReservationItem.keyFor(newNormalized));
        newReservation.setUserId(id);

        Key oldReservationKey = Key.builder()
                .partitionValue(EmailReservationItem.keyFor(oldNormalized))
                .build();

        try {
            enhancedClient.transactWriteItems(TransactWriteItemsEnhancedRequest.builder()
                    .addPutItem(emailReservationTable, TransactPutItemEnhancedRequest
                            .builder(EmailReservationItem.class)
                            .item(newReservation)
                            .conditionExpression(NOT_EXISTS)
                            .build())
                    .addDeleteItem(emailReservationTable, oldReservationKey)
                    .addUpdateItem(userTable, current)
                    .build());
        } catch (TransactionCanceledException e) {
            if (DynamoDbSupport.isConditionFailure(e.cancellationReasons(), 0)) {
                throw new DuplicateResourceException("Email já cadastrado por outro usuário");
            }
            if (DynamoDbSupport.isConditionFailure(e.cancellationReasons(), 2)) {
                throw new ConcurrentUpdateException(
                        "O registro foi alterado por outra operação. Tente novamente.");
            }
            RuntimeException translated = DynamoDbSupport.translateGeneric(e);
            throw translated != null ? translated : e;
        } catch (RuntimeException e) {
            RuntimeException translated = DynamoDbSupport.translateGeneric(e);
            throw translated != null ? translated : e;
        }

        return toRecord(current);
    }

    @Override
    public void delete(String id) {
        UserItem current = userTable.getItem(GetItemEnhancedRequest.builder()
                .key(Key.builder().partitionValue(UserItem.keyFor(id)).build())
                .consistentRead(true)
                .build());
        if (current == null) {
            throw new ResourceNotFoundException("Usuário não encontrado");
        }

        try {
            enhancedClient.transactWriteItems(TransactWriteItemsEnhancedRequest.builder()
                    .addDeleteItem(userTable, Key.builder().partitionValue(UserItem.keyFor(id)).build())
                    .addDeleteItem(emailReservationTable,
                            Key.builder().partitionValue(EmailReservationItem.keyFor(current.getEmailNormalized())).build())
                    .build());
        } catch (RuntimeException e) {
            RuntimeException translated = DynamoDbSupport.translateGeneric(e);
            throw translated != null ? translated : e;
        }
    }

    private static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private UserRecord toRecord(UserItem item) {
        return new UserRecord(
                item.getId(),
                item.getName(),
                item.getEmail(),
                item.getPasswordHash(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }
}
