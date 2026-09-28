package com.juanp.saaspa.ia.api;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>El texto que responde cada caso sale de {@link ProblemCode}: es el que el backend reenvia al widget, asi
 * que tiene que ser apto para la clienta (ADR 0017). El <strong>motivo tecnico</strong> —que campo no cuadro,
 * que fallo el backend, que excepcion se lanzo— se registra en el log y nunca se pone en el cuerpo (R8): los
 * logs solo llevan el mensaje tecnico o el tipo de excepcion, sin token ni datos personales.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(InvalidTurnContextException.class)
	public ProblemDetail invalidTurnContext(InvalidTurnContextException exception) {
		// El mensaje de la excepcion nombra campos internos (tenantId, conversationId...): sirve para el
		// log, no para la clienta.
		log.warn("Turno con contexto invalido: {}", exception.getMessage());
		return problem(ProblemCode.TURN_CONTEXT_MISMATCH);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail invalidBody(MethodArgumentNotValidException exception) {
		List<String> errors = exception.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getField() + ": " + error.getDefaultMessage())
				.toList();
		// Los nombres de campo y los mensajes de validacion son tecnicos: van al log y a `errors`, nunca al
		// `detail`, que es lo que lee la clienta.
		log.warn("Turno con cuerpo invalido: {}", errors);
		ProblemDetail problem = problem(ProblemCode.INVALID_BODY);
		problem.setProperty("errors", errors);
		return problem;
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ProblemDetail unreadableBody(HttpMessageNotReadableException exception) {
		log.warn("Turno con cuerpo no interpretable: {}", exception.getClass().getSimpleName());
		return problem(ProblemCode.MALFORMED_BODY);
	}

	@ExceptionHandler(UnsupportedAgentException.class)
	public ProblemDetail unsupportedAgent(UnsupportedAgentException exception) {
		log.warn("Turno para un agente no implementado: {}", exception.getMessage());
		return problem(ProblemCode.AGENT_NOT_IMPLEMENTED);
	}

	@ExceptionHandler(BackendUnavailableException.class)
	public ProblemDetail backendUnavailable(BackendUnavailableException exception) {
		log.warn("Turno sin respuesta del backend: {}", exception.getClass().getSimpleName());
		return problem(ProblemCode.BACKEND_UNAVAILABLE);
	}

	@ExceptionHandler(BackendException.class)
	public ProblemDetail backendError(BackendException exception) {
		log.warn("Turno con error del backend: {}", exception.getClass().getSimpleName());
		return problem(ProblemCode.BACKEND_ERROR);
	}

	@ExceptionHandler(LlmTimeoutException.class)
	public ProblemDetail llmTimeout(LlmTimeoutException exception) {
		log.warn("Turno sin respuesta del modelo: {}", exception.getClass().getSimpleName());
		ProblemDetail problem = problem(ProblemCode.MODEL_TIMEOUT);
		if (exception.turnId() != null) {
			// ADR 0014: el turnId correlaciona este 504 con la fila DEADLINE de ia.turn_log y con NestJS.
			problem.setProperty("turnId", exception.turnId());
		}
		return problem;
	}

	@ExceptionHandler(CostLimitExceededException.class)
	public ProblemDetail costLimitExceeded(CostLimitExceededException exception) {
		log.warn("Turno rechazado por el tope de coste: {} {}", exception.scope(), exception.measure());
		ProblemDetail problem = problem(ProblemCode.COST_LIMIT);
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
		log.warn("Turno de un tenant no permitido (A-03)");
		return problem(ProblemCode.TENANT_NOT_ALLOWED);
	}

	@ExceptionHandler(AuthenticationException.class)
	public ProblemDetail unauthenticated(AuthenticationException exception) {
		log.warn("Peticion sin autenticar: {}", exception.getClass().getSimpleName());
		return problem(ProblemCode.UNAUTHENTICATED);
	}

	@ExceptionHandler(Exception.class)
	public ProblemDetail unexpected(Exception exception) {
		log.error("Turno con error inesperado: {}", exception.getClass().getSimpleName());
		return problem(ProblemCode.UNEXPECTED);
	}

	private static ProblemDetail problem(ProblemCode code) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), code.publicDetail());
		problem.setTitle(code.title());
		problem.setProperty("code", code.name());
		return problem;
	}
}
