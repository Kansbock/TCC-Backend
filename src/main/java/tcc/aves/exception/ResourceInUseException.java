package tcc.aves.exception;

/**
 * Recurso não pode ser removido porque outros registros dependem dele.
 * Mapeada para HTTP 409.
 */
public class ResourceInUseException extends RuntimeException {

    public ResourceInUseException(String message) {
        super(message);
    }
}
