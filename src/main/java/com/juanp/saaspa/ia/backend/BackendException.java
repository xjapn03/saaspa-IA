package com.juanp.saaspa.ia.backend;

import java.util.Optional;

/**
 * Error de una llamada al backend NestJS.
 *
 * <p>Se lanza cuando el backend responde con un estado no exitoso, con un cuerpo vacio o con una
 * respuesta que no se puede interpretar. {@link BackendUnavailableException} cubre el caso en que
 * no hubo respuesta (timeout, conexion rechazada, DNS).
 *
 * <p>Regla R8 de AGENTS.md: el cuerpo de la respuesta puede contener datos; no se registra en logs
 * de nivel INFO ni superior.
 */
public class BackendException extends RuntimeException {

	private static final int MAX_BODY_LENGTH = 500;

	private final Integer status;

	private final String responseBody;

	BackendException(String message, Integer status, String responseBody, Throwable cause) {
		super(message, cause);
		this.status = status;
		this.responseBody = responseBody;
	}

	/** Estado HTTP devuelto por el backend, si hubo respuesta. */
	public Optional<Integer> status() {
		return Optional.ofNullable(this.status);
	}

	/** Cuerpo crudo de la respuesta (truncado), si hubo respuesta. */
	public Optional<String> responseBody() {
		return Optional.ofNullable(this.responseBody);
	}

	static BackendException fromStatus(int status, String path, String body) {
		String message = "El backend respondio HTTP %d al llamar %s".formatted(status, path);
		return new BackendException(message, status, truncate(body), null);
	}

	static BackendException emptyBody(String path) {
		String message = "El backend devolvio una respuesta vacia al llamar %s".formatted(path);
		return new BackendException(message, null, null, null);
	}

	static BackendException unexpected(String path, String detail, Throwable cause) {
		String message = "Fallo inesperado al llamar %s: %s".formatted(path, detail);
		return new BackendException(message, null, null, cause);
	}

	private static String truncate(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		String flat = body.replaceAll("\\s+", " ").trim();
		return flat.length() <= MAX_BODY_LENGTH ? flat : flat.substring(0, MAX_BODY_LENGTH) + "...";
	}
}
