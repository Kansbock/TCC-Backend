package tcc.aves.controller;

import org.springframework.beans.factory.ObjectProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tcc.aves.notification.FcmGateway;
import tcc.aves.notification.NotificationJobProcessor;
import tcc.aves.repository.NotificationRepository;
import tcc.aves.security.AuthenticatedUser;
import tcc.aves.security.JwtAuthenticationFilter;

import java.time.Clock;

@RestController
@RequestMapping("/api/internal/notification-jobs")
public class NotificationJobController {
    private final String runnerId;
    private final boolean enabled;
    private final NotificationRepository repository;
    private final ObjectProvider<FcmGateway> gateway;

    public NotificationJobController(NotificationRepository repository, ObjectProvider<FcmGateway> gateway,
            @Value("${app.notifications.runner-user-id:}") String runnerId,
            @Value("${app.notifications.processing-enabled:false}") boolean enabled) {
        this.repository = repository;
        this.gateway = gateway;
        this.runnerId = runnerId;
        this.enabled = enabled;
    }

    @PostMapping("/process")
    public NotificationJobProcessor.Batch process(@AuthenticationPrincipal AuthenticatedUser user,
                                                  HttpServletRequest request,
                                                  @RequestBody(required = false) String body) {
        if (runnerId.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Executor não configurado");
        if (!runnerId.equals(user.id())) throw new AccessDeniedException("Conta não autorizada a processar notificações");
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Processamento desabilitado");
        if (body != null && !body.isBlank() && !"{}".equals(body.trim())) {
            throw new IllegalArgumentException("O executor não aceita parâmetros de envio");
        }
        FcmGateway sender;
        try {
            sender = gateway.getObject();
        } catch (BeanCreationException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configuração FCM indisponível");
        }
        long started = (long) request.getAttribute(JwtAuthenticationFilter.NOTIFICATION_REQUEST_START);
        long deadline = started + 20_000_000_000L;
        return new NotificationJobProcessor(repository, sender, Clock.systemUTC()).process(
                () -> (int) Math.max(0, (deadline - System.nanoTime()) / 1_000_000));
    }
}
