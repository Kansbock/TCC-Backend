package tcc.aves.exception;

/**
 * O DynamoDB está indisponível, sofrendo throttling persistente ou a
 * configuração de tabela/índice é inválida. Mapeada para HTTP 503: o
 * problema é de infraestrutura, não da requisição do cliente.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
