package tcc.aves.repository;

import java.util.Optional;

/**
 * Port de persistência de usuários. Não depende de JPA, DynamoDB ou de
 * qualquer outro mecanismo específico — só expõe as operações que a camada
 * de aplicação (services) realmente usa.
 */
public interface UserRepository {

    /**
     * Cria um novo usuário. Lança {@code DuplicateResourceException} se o
     * e-mail já estiver em uso.
     */
    UserRecord create(String name, String email, String passwordHash);

    Optional<UserRecord> findById(String id);

    /** Lookup direto por e-mail normalizado — nunca faz Scan. */
    Optional<UserRecord> findByEmail(String email);

    /**
     * Atualiza nome/e-mail. Lança {@code ResourceNotFoundException} se o
     * usuário não existir, {@code DuplicateResourceException} se o novo
     * e-mail já pertencer a outro usuário, ou {@code ConcurrentUpdateException}
     * se o registro tiver sido alterado concorrentemente.
     */
    UserRecord update(String id, String name, String email);

    /** Lança {@code ResourceNotFoundException} se o usuário não existir. */
    void delete(String id);
}
