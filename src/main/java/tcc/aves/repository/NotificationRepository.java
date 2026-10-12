package tcc.aves.repository;

import java.time.Instant;
import java.util.List;

public interface NotificationRepository {
    record Preferences(Instant startedAt, boolean pushEnabled, long unreadCount) {}
    record Notice(String id, String sightingId, Instant createdAt, Instant readAt, String title, String body) {}
    record Page<T>(List<T> items, String nextCursor) {}
    record Participant(String key, String userId, Instant startedAt) {}
    record Device(String userId, String installationId, String token, String generation) {}
    record Event(String sightingId, Instant createdAt) {
        public String title() { return "Novo avistamento registrado"; }
        public String body() { return "Um novo registro de ave está disponível no AvistAI."; }
    }
    record Progress(String historyCursor, boolean historyDone, String pushCursor, String deviceCursor,
                    boolean complete) {}
    record Attempt(String status, int count, Instant nextAttemptAt, Instant leaseUntil) {}
    record Job(Event event, String key) {}

    Preferences subscribe(String userId, Instant now);
    Preferences preferences(String userId);
    Preferences setPreference(String userId, boolean enabled);
    void registerDevice(String userId, String installationId, String token);
    void deactivateDevice(String userId, String installationId);
    void deactivateGeneration(Device device);
    Page<Notice> inbox(String userId, int limit, String cursor);
    Notice markRead(String userId, String id, Instant now);
    Page<Participant> participants(Event event, String cursor);
    Page<Device> devices(String userId, String cursor);
    boolean createNotice(String userId, Event event);
    boolean eligible(Device device);
    Progress acquireEvent(Event event, String owner, Instant now);
    void checkpoint(Event event, String owner, Progress progress);
    void releaseEvent(Event event, String owner);
    Attempt attempt(Event event, Device device);
    boolean claimAttempt(Event event, Device device, Instant now);
    void finishAttempt(Event event, Device device, String status, String messageId,
                       String errorCode, Instant nextAttemptAt);
    List<Job> dueJobs(Instant now, int limit);
    void completeJob(Job job);
    void deferJob(Job job, Instant nextAt, String errorCode, boolean terminal);
}
