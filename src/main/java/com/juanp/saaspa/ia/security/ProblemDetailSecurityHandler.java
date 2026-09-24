package com.juanp.saaspa.ia.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import tools.jackson.databind.ObjectMapper;

/**
 * Respuestas de seguridad con {@link ProblemDetail} (RFC 9457), el mismo formato de error que
 * declara el contrato de chat ({@code application/problem+json}).
 *
 * <p>Los mensajes son genericos a proposito: no revelan si el fallo fue la clave de servicio, la
 * firma del turn token o su caducidad, y nunca incluyen el token (regla R8).
 */
public class ProblemDetailSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final ObjectMapper objectMapper;

	public ProblemDetailSecurityHandler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
			throws IOException {
		write(response, HttpStatus.UNAUTHORIZED, "No autorizado", detail(exception));
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
			throws IOException {
		write(response, HttpStatus.FORBIDDEN, "Acceso denegado", "El turn token no permite esta operacion");
	}

	/**
	 * Respuesta 401 reutilizable por los filtros de seguridad.
	 *
	 * @param response respuesta HTTP en curso
	 * @param detail mensaje generico para el cliente
	 */
	public void unauthorized(HttpServletResponse response, String detail) throws IOException {
		write(response, HttpStatus.UNAUTHORIZED, "No autorizado", detail);
	}

	private void write(HttpServletResponse response, HttpStatus status, String title, String detail) throws IOException {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		this.objectMapper.writeValue(response.getOutputStream(), problem);
	}

	private static String detail(AuthenticationException exception) {
		if (exception instanceof InsufficientAuthenticationException) {
			return "Falta el turn token";
		}
		if (exception instanceof BadCredentialsException) {
			return "Turn token invalido, expirado o de otra audiencia";
		}
		return "Autenticacion requerida";
	}
}
