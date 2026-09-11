package tcc.aves.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tcc.aves.dto.ApiError;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Serializa {@link ApiError} direto na resposta.
 *
 * <p>Necessário porque os handlers de segurança rodam na cadeia de filtros,
 * antes do {@code DispatcherServlet}, e portanto fora do alcance do
 * {@code @RestControllerAdvice}. Sem isso, 401 e 403 sairiam com corpo vazio.
 */
@Component
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request,
                      HttpServletResponse response,
                      HttpStatus status,
                      String message) throws IOException {

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ApiError body = ApiError.of(
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI()
        );

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
