package com.juanp.saaspa.ia.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.juanp.saaspa.ia.api.ProblemCode;

import tools.jackson.databind.ObjectMapper;

/**
 * Respuestas de seguridad con {@link ProblemDetail} (RFC 9457), el mismo formato de error que
 * declara el contrato de chat ({@code application/problem+json}).
 *
 * <p>El texto que recibe quien llama sale de {@link ProblemCode} (apto para la clienta, ADR 0017) y el
 * <strong>motivo tecnico</strong> que lo explica —que fallo: la clave de servicio, la firma del turn token, su
 * caducidad, su audiencia— se registra en el log. Antes este manejador no registraba nada: un 401 o un 403
 * repetidos no dejaban ni rastro, y ademas el cliente recibia jerga interna ("turn token", "clave de
 * servicio") y podia distinguir el caso exacto.
 */
public class ProblemDetailSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private static final Logger log = LoggerFactory.getLogger(ProblemDetailSecurityHandler.class);

	private final ObjectMapper objectMapper;

	public ProblemDetailSecurityHandler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
			throws IOException {
		log.warn("Peticion rechazada por autenticacion: {}", reason(exception));
		unauthorized(response);
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
			throws IOException {
		log.warn("Peticion rechazada por autorizacion: {}", exception.getClass().getSimpleName());
		write(response, ProblemCode.ACCESS_DENIED);
	}

	/**
	 * Respuesta 401 reutilizable por los filtros de seguridad, que son quienes saben que comprobacion fallo.
	 *
	 * @param response respuesta HTTP en curso
	 */
	public void unauthorized(HttpServletResponse response) throws IOException {
		write(response, ProblemCode.UNAUTHENTICATED);
	}

	private void write(HttpServletResponse response, ProblemCode code) throws IOException {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), code.publicDetail());
		problem.setTitle(code.title());
		problem.setProperty("code", code.name());
		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		this.objectMapper.writeValue(response.getOutputStream(), problem);
	}

	private static String reason(AuthenticationException exception) {
		if (exception instanceof InsufficientAuthenticationException) {
			return "falta el turn token";
		}
		if (exception instanceof BadCredentialsException) {
			return "turn token invalido, expirado o de otra audiencia";
		}
		return exception.getClass().getSimpleName();
	}
}
