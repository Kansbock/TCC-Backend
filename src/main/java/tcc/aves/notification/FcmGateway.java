package tcc.aves.notification;

import tcc.aves.repository.NotificationRepository.Device;
import tcc.aves.repository.NotificationRepository.Event;

import java.time.Duration;

public interface FcmGateway {
    enum Outcome { ACCEPTED, UNREGISTERED, RETRYABLE, CONFIGURATION, INVALID_PAYLOAD, INVALID_TOKEN }
    record Result(Outcome outcome, String messageId, String errorCode, Duration retryAfter) {}
    Result send(Event event, Device device);
}
