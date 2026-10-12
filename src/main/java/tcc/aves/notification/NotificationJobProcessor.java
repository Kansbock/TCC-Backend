package tcc.aves.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tcc.aves.exception.ConcurrentUpdateException;
import tcc.aves.repository.NotificationRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.function.IntSupplier;

public class NotificationJobProcessor {
    public record Batch(int completed, int deferred, int failed) {}
    private static final Logger log = LoggerFactory.getLogger(NotificationJobProcessor.class);
    private final NotificationRepository repository;
    private final NotificationDispatcher dispatcher;
    private final Clock clock;

    public NotificationJobProcessor(NotificationRepository repository, FcmGateway gateway, Clock clock) {
        this.repository = repository;
        this.dispatcher = new NotificationDispatcher(repository, gateway, clock);
        this.clock = clock;
    }

    public Batch process() {
        long deadline = System.nanoTime() + 20_000_000_000L;
        return process(() -> (int) Math.max(0, (deadline - System.nanoTime()) / 1_000_000));
    }

    public Batch process(IntSupplier remaining) {
        int completed = 0, deferred = 0, failed = 0;
        var jobs = repository.dueJobs(clock.instant(), 10);
        for (int i = 0; i < jobs.size(); i++) {
            if (remaining.getAsInt() < 15000) { deferred += jobs.size() - i; break; }
            var job = jobs.get(i);
            try {
                dispatcher.dispatch(job.event(), remaining);
                repository.completeJob(job);
                completed++;
            } catch (ConcurrentUpdateException e) {
                deferred++;
                log.info("notification_job_deferred category=CONCURRENT_UPDATE");
            } catch (NotificationDispatchException e) {
                Instant next = e.nextAttemptAt().isAfter(clock.instant())
                        ? e.nextAttemptAt() : clock.instant().plusSeconds(2);
                if (defer(job, next, e.code(), e.terminal()) && e.terminal()) failed++; else deferred++;
                log.warn("notification_job_incomplete category={}", e.code());
            } catch (IllegalStateException e) {
                defer(job, clock.instant().plusSeconds(30), e.getClass().getSimpleName(), false);
                deferred++;
                log.warn("notification_job_incomplete category={}", e.getClass().getSimpleName());
            }
        }
        return new Batch(completed, deferred, failed);
    }

    private boolean defer(NotificationRepository.Job job, Instant next, String code, boolean terminal) {
        try {
            repository.deferJob(job, next, code, terminal);
            return true;
        } catch (ConcurrentUpdateException e) {
            log.info("notification_job_deferred category=CONCURRENT_UPDATE");
            return false;
        }
    }
}
