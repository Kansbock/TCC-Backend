package tcc.aves.exception;

/**
 * O item foi alterado por outra operação entre a leitura e a escrita
 * (falha de condição/otimistic lock no DynamoDB). Mapeada para HTTP 409.
 */
public class ConcurrentUpdateException extends RuntimeException {

    public ConcurrentUpdateException(String message) {
        super(message);
    }
}
