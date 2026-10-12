package tcc.aves.notification;

import java.time.Instant;

public class NotificationDispatchException extends IllegalStateException {
    private final Instant nextAttemptAt;
    private final String code;
    private final boolean terminal;

    public NotificationDispatchException(String code, Instant nextAttemptAt, boolean terminal) {
        super("Processamento de notificação incompleto: " + code);
        this.code = code;
        this.nextAttemptAt = nextAttemptAt;
        this.terminal = terminal;
    }

    public Instant nextAttemptAt() { return nextAttemptAt; }
    public String code() { return code; }
    public boolean terminal() { return terminal; }
}
