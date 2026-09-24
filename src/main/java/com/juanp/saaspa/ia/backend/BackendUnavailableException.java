package com.juanp.saaspa.ia.backend;

/**
 * El backend NestJS no respondio: timeout, conexion rechazada o error de red.
 *
 * <p>Equivale al caso "sin respuesta HTTP" de {@link BackendException}; la capa de API lo traducira
 * a un {@code ProblemDetail} 502/504.
 */
public class BackendUnavailableException extends BackendException {

	public BackendUnavailableException(String path, Throwable cause) {
		super(
				"No se pudo llamar al backend (%s): %s".formatted(path, cause.getClass().getSimpleName()),
				null,
				null,
				cause);
	}
}
