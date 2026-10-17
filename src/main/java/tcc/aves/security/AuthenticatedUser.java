package tcc.aves.security;

/**
 * Principal guardado no {@code SecurityContext} após a validação do JWT.
 *
 * <p>Carrega o {@code id} além do e-mail justamente para permitir a checagem de
 * propriedade da conta sem uma consulta extra ao banco em cada controller
 * (ver {@code @PreAuthorize("#id == principal.id")} em {@code UserController}).
 */
public record AuthenticatedUser(String id, String email, UserRole role) {
}
