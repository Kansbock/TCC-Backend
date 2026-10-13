package tcc.aves.service;

import org.springframework.stereotype.Service;
import tcc.aves.exception.ResourceNotFoundException;
import tcc.aves.repository.NotificationRepository;
import tcc.aves.repository.UserRepository;

import java.time.Instant;

@Service
public class NotificationService {
    private final NotificationRepository repository;
    private final UserRepository users;

    public NotificationService(NotificationRepository repository, UserRepository users) {
        this.repository = repository;
        this.users = users;
    }

    public NotificationRepository.Preferences subscribe(String userId) {
        requireUser(userId);
        return repository.subscribe(userId, Instant.now());
    }

    public NotificationRepository.Preferences preferences(String userId) {
        requireUser(userId);
        return repository.preferences(userId);
    }

    public NotificationRepository.Preferences preference(String userId, boolean enabled) {
        requireUser(userId);
        return repository.setPreference(userId, enabled);
    }

    public void register(String userId, String installationId, String token) {
        requireUser(userId);
        validateId(installationId);
        if (token == null || !token.matches("[A-Za-z0-9_:\\-]{20,4096}") || token.startsWith("ExponentPushToken")) {
            throw new IllegalArgumentException("Token FCM inválido");
        }
        repository.registerDevice(userId, installationId, token);
    }

    public void deactivate(String userId, String installationId) {
        validateId(installationId);
        repository.deactivateDevice(userId, installationId);
    }

    public NotificationRepository.Page<NotificationRepository.Notice> inbox(String userId, int limit, String cursor) {
        requireUser(userId);
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Limite deve estar entre 1 e 50");
        return repository.inbox(userId, limit, cursor);
    }

    public NotificationRepository.Notice read(String userId, String id) {
        requireUser(userId);
        validateId(id);
        return repository.markRead(userId, id, Instant.now());
    }

    public void clear(String userId, String idempotencyKey) {
        requireUser(userId);
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Idempotency-Key inválida ou ausente");
        }
        repository.clearInbox(userId, idempotencyKey, Instant.now());
    }

    private void requireUser(String id) {
        if (users.findById(id).isEmpty()) throw new ResourceNotFoundException("Conta não encontrada");
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Identificador inválido");
        }
    }
}
