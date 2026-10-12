package tcc.aves.notification;

import tcc.aves.repository.NotificationRepository;
import tcc.aves.repository.NotificationRepository.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;

public class NotificationDispatcher {
    private final NotificationRepository repository;
    private final FcmGateway gateway;
    private final Clock clock;

    public NotificationDispatcher(NotificationRepository repository, FcmGateway gateway, Clock clock) {
        this.repository = repository;
        this.gateway = gateway;
        this.clock = clock;
    }

    public void dispatch(Event event, IntSupplier remainingMillis) {
        String owner = UUID.randomUUID().toString();
        Progress state = repository.acquireEvent(event, owner, clock.instant());
        try {
            if (state.complete()) return;
            if (!state.historyDone()) {
                String cursor = state.historyCursor();
                while (true) {
                    deadline(remainingMillis);
                    Page<Participant> page = repository.participants(event, cursor);
                    for (Participant participant : page.items()) {
                        deadline(remainingMillis);
                        repository.createNotice(participant.userId(), event);
                    }
                    cursor = page.nextCursor();
                    state = new Progress(cursor, cursor == null, state.pushCursor(), state.deviceCursor(), false);
                    repository.checkpoint(event, owner, state);
                    if (cursor == null) break;
                }
            }
            String participantCursor = state.pushCursor();
            String deviceCursor = state.deviceCursor();
            while (true) {
                deadline(remainingMillis);
                Page<Participant> page = repository.participants(event, participantCursor);
                for (Participant participant : page.items()) {
                    if (event.createdAt().isBefore(participant.startedAt())) {
                        participantCursor = participant.key();
                        continue;
                    }
                    while (true) {
                        deadline(remainingMillis);
                        Page<Device> devices = repository.devices(participant.userId(), deviceCursor);
                        for (Device device : devices.items()) {
                            deadline(remainingMillis);
                            push(event, device, remainingMillis);
                        }
                        deviceCursor = devices.nextCursor();
                        state = new Progress(state.historyCursor(), true, participantCursor, deviceCursor, false);
                        repository.checkpoint(event, owner, state);
                        if (deviceCursor == null) break;
                    }
                    participantCursor = participant.key();
                    state = new Progress(state.historyCursor(), true, participantCursor, null, false);
                    repository.checkpoint(event, owner, state);
                }
                if (page.nextCursor() == null) break;
                participantCursor = page.nextCursor();
            }
            repository.checkpoint(event, owner, new Progress(state.historyCursor(), true, participantCursor, null, true));
        } finally {
            repository.releaseEvent(event, owner);
        }
    }

    private void push(Event event, Device device, IntSupplier remaining) {
        if (!repository.eligible(device)) return;
        deadline(remaining);
        Instant now = clock.instant();
        Attempt previous = repository.attempt(event, device);
        if ("ACCEPTED".equals(previous.status()) || "SKIPPED".equals(previous.status())) return;
        if (previous.count() >= 5 || "FAILED".equals(previous.status())) {
            throw new NotificationDispatchException("ATTEMPTS_EXHAUSTED", now, true);
        }
        if (now.isBefore(previous.nextAttemptAt()) || now.isBefore(previous.leaseUntil())) {
            throw new NotificationDispatchException("RETRY_NOT_DUE",
                    previous.nextAttemptAt().isAfter(previous.leaseUntil()) ? previous.nextAttemptAt() : previous.leaseUntil(), false);
        }
        if (!repository.claimAttempt(event, device, now)) {
            if (repository.eligible(device)) throw new IllegalStateException("Tentativa FCM concorrente");
            return;
        }
        FcmGateway.Result result = gateway.send(event, device);
        switch (result.outcome()) {
            case ACCEPTED -> repository.finishAttempt(event, device, "ACCEPTED", result.messageId(), null, Instant.EPOCH);
            case UNREGISTERED -> {
                repository.deactivateGeneration(device);
                repository.finishAttempt(event, device, "SKIPPED", null, "UNREGISTERED", Instant.EPOCH);
            }
            case RETRYABLE, CONFIGURATION -> {
                long base = "HTTP_429".equals(result.errorCode()) || result.outcome() == FcmGateway.Outcome.CONFIGURATION ? 60 : 2;
                long seconds = base * (1L << previous.count());
                Duration backoff = Duration.ofMillis((long) (seconds * 1000 * ThreadLocalRandom.current().nextDouble(1.0, 1.2)));
                Duration delay = result.retryAfter().compareTo(backoff) > 0 ? result.retryAfter() : backoff;
                repository.finishAttempt(event, device, "WAITING", null, result.errorCode(), now.plus(delay));
                throw new NotificationDispatchException(result.errorCode(), now.plus(delay), false);
            }
            case INVALID_PAYLOAD, INVALID_TOKEN -> {
                repository.finishAttempt(event, device, "FAILED", null, result.errorCode(), Instant.EPOCH);
                throw new NotificationDispatchException(result.errorCode(), now, true);
            }
        }
    }

    private static void deadline(IntSupplier remaining) {
        if (remaining.getAsInt() < 15000) throw new IllegalStateException("Evento incompleto próximo ao timeout");
    }
}
