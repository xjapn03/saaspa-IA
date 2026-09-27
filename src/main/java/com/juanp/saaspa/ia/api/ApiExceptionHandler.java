package com.juanp.saaspa.ia.api;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.juanp.saaspa.ia.backend.BackendException;
import com.juanp.saaspa.ia.backend.BackendUnavailableException;
import com.juanp.saaspa.ia.usage.CostLimitExceededException;

/**
 * Errores de la API con {@link ProblemDetail} (RFC 9457), el formato del contrato de chat.
 *
 * <p>Los mensajes son genericos a proposito: no revelan detalles internos, no incluyen el token ni
 * datos personales (regla R8) y los fallos inesperados solo se registran por tipo de excepcion.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(InvalidTurnContextException.class)
	public ProblemDetail invalidTurnContext(InvalidTurnContextException exception) {
		return problem(HttpStatus.BAD_REQUEST, "Peticion invalida", exception.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail invalidBody(MethodArgumentNotValidException exception) {
		List<String> errors = exception.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getField() + ": " + error.getDefaultMessage())
				.toList();
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Peticion invalida",
				"El cuerpo de la peticion no es valido");
		problem.setProperty("errors", errors);
		return problem;
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ProblemDetail unreadableBody(HttpMessageNotReadableException exception) {
		return problem(HttpStatus.BAD_REQUEST, "Peticion invalida", "El cuerpo de la peticion no es JSON valido");
	}

	@ExceptionHandler(UnsupportedAgentException.class)
	public ProblemDetail unsupportedAgent(UnsupportedAgentException exception) {
		return problem(HttpStatus.NOT_IMPLEMENTED, "No implementado", exception.getMessage());
	}

	@ExceptionHandler(BackendUnavailableException.class)
	public ProblemDetail backendUnavailable(BackendUnavailableException exception) {
		log.warn("Turno sin respuesta del backend: {}", exception.getClass().getSimpleName());
		return problem(HttpStatus.BAD_GATEWAY, "Sistema de agenda no disponible",
				"El sistema de agenda no respondio; intenta de nuevo en un momento");
	}

	@ExceptionHandler(BackendException.class)
	public ProblemDetail backendError(BackendException exception) {
		log.warn("Turno con error del backend: {}", exception.getClass().getSimpleName());
		return problem(HttpStatus.BAD_GATEWAY, "Sistema de agenda no disponible",
				"El sistema de agenda no pudo responder la consulta");
	}

	@ExceptionHandler(LlmTimeoutException.class)
	public ProblemDetail llmTimeout(LlmTimeoutException exception) {
		log.warn("Turno sin respuesta del modelo: {}", exception.getClass().getSimpleName());
		ProblemDetail problem = problem(HttpStatus.GATEWAY_TIMEOUT, "Modelo no disponible",
				"El modelo no respondio a tiempo; intenta de nuevo en un momento");
		if (exception.turnId() != null) {
			// ADR 0014: el turnId correlaciona este 504 con la fila DEADLINE de ia.turn_log y con NestJS.
			problem.setProperty("turnId", exception.turnId());
		}
		return problem;
	}

	@ExceptionHandler(CostLimitExceededException.class)
	public ProblemDetail costLimitExceeded(CostLimitExceededException exception) {
		log.warn("Turno rechazado por el tope de coste: {} {}", exception.scope(), exception.measure());
		ProblemDetail problem = problem(HttpStatus.TOO_MANY_REQUESTS, "Limite de uso alcanzado",
				"Se alcanzo el limite de uso de este asistente; intenta de nuevo mas tarde");
		// ADR 0010: el ambito y la medida no son PII y ayudan a diagnosticar (y a calibrar en la Fase 5).
		problem.setProperty("scope", exception.scope().name().toLowerCase(Locale.ROOT));
		problem.setProperty("measure", exception.measure().name().toLowerCase(Locale.ROOT));
		problem.setProperty("measured", exception.measured());
		problem.setProperty("limit", exception.limit());
		problem.setProperty("window", exception.window().toString());
		return problem;
	}

	@ExceptionHandler(TenantNotAllowedException.class)
	public ProblemDetail tenantNotAllowed(TenantNotAllowedException exception) {
		return problem(HttpStatus.FORBIDDEN, "Tenant no permitido",
				"El tenant del turno no esta permitido en este servicio");
	}

	@ExceptionHandler(AuthenticationException.class)
	public ProblemDetail unauthenticated(AuthenticationException exception) {
		return problem(HttpStatus.UNAUTHORIZED, "No autorizado", "Falta el turn token o no es valido");
	}

	@ExceptionHandler(Exception.class)
	public ProblemDetail unexpected(Exception exception) {
		log.error("Turno con error inesperado: {}", exception.getClass().getSimpleName());
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno",
				"Ocurrio un error inesperado atendiendo el turno");
	}

	private static ProblemDetail problem(HttpStatus status, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		return problem;
	}
}
