package tcc.aves.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tcc.aves.repository.UserRepository;

import java.io.IOException;
import java.util.List;

/**
 * Autentica a requisição a partir do JWT no header {@code Authorization}.
 *
 * <p>A assinatura válida não basta: o usuário é buscado no banco a cada
 * requisição (por id, não por e-mail). Assim o token de uma conta já
 * excluída não continua funcionando até expirar, e alterar o e-mail do
 * usuário não invalida tokens já emitidos.
 *
 * <p>O principal é um {@link AuthenticatedUser} (com id), e não a String do
 * e-mail, para que os controllers possam verificar a propriedade do recurso.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";
    public static final String NOTIFICATION_REQUEST_START = "notificationRequestStartedNanos";

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if ("/api/internal/notification-jobs/process".equals(request.getRequestURI())) {
            request.setAttribute(NOTIFICATION_REQUEST_START, System.nanoTime());
        }
        String authHeader = request.getHeader(HEADER);

        if (authHeader != null
                && authHeader.startsWith(PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            String token = authHeader.substring(PREFIX.length());

            if (jwtUtil.isTokenValid(token)) {
                String userId = jwtUtil.extractUserId(token);

                // Confirma que a conta ainda existe antes de autenticar.
                userRepository.findById(userId).ifPresent(user -> {
                    AuthenticatedUser principal = new AuthenticatedUser(user.id(), user.email(), user.role());

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    principal,
                                    null,
                                    user.role() == UserRole.ADMIN
                                            ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                            : List.of());
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                });
            }
        }

        filterChain.doFilter(request, response);
    }
}
