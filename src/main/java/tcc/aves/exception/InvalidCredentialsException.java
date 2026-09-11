package tcc.aves.exception;

/** E-mail ou senha inválidos. Mapeada para HTTP 401. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
