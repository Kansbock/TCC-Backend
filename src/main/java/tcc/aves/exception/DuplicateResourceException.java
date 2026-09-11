package tcc.aves.exception;

/** Violação de unicidade (e-mail, nome científico). Mapeada para HTTP 409. */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
