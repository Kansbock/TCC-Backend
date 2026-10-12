package tcc.aves.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tcc.aves.repository.NotificationRepository;
import tcc.aves.security.AuthenticatedUser;
import tcc.aves.service.NotificationService;

@RestController
@RequestMapping("/api")
public class NotificationController {
    public record PreferenceRequest(@NotNull Boolean pushEnabled) {}
    public record DeviceRequest(@NotBlank @Size(max = 4096) String token) {}
    public record UnreadResponse(long unreadCount) {}
    private final NotificationService service;

    public NotificationController(NotificationService service) { this.service = service; }

    @PostMapping("/notification-subscriptions")
    public NotificationRepository.Preferences subscribe(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.subscribe(user.id());
    }

    @GetMapping("/notification-preferences")
    public NotificationRepository.Preferences preferences(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.preferences(user.id());
    }

    @PutMapping("/notification-preferences")
    public NotificationRepository.Preferences preference(@AuthenticationPrincipal AuthenticatedUser user,
                                                          @Valid @RequestBody PreferenceRequest body) {
        return service.preference(user.id(), body.pushEnabled());
    }

    @PutMapping("/notification-devices/{installationId}")
    public ResponseEntity<Void> device(@AuthenticationPrincipal AuthenticatedUser user,
                                      @PathVariable String installationId, @Valid @RequestBody DeviceRequest body) {
        service.register(user.id(), installationId, body.token());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/notification-devices/{installationId}")
    public ResponseEntity<Void> deactivate(@AuthenticationPrincipal AuthenticatedUser user,
                                          @PathVariable String installationId) {
        service.deactivate(user.id(), installationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/notifications")
    public NotificationRepository.Page<NotificationRepository.Notice> inbox(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor) {
        return service.inbox(user.id(), limit, cursor);
    }

    @GetMapping("/notifications/unread-count")
    public UnreadResponse unread(@AuthenticationPrincipal AuthenticatedUser user) {
        return new UnreadResponse(service.preferences(user.id()).unreadCount());
    }

    @PatchMapping("/notifications/{id}/read")
    public NotificationRepository.Notice read(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return service.read(user.id(), id);
    }
}
