package com.juanp.saaspa.ia.api;

/**
 * El turno supero el deadline configurado ({@code saaspa.llm.turn-deadline}) esperando al modelo.
 *
 * <p>La capa de API lo traduce a un {@code ProblemDetail} 504 que incluye el {@code turnId}, para poder
 * correlacionar el fallo con el lado de NestJS y con la fila registrada en {@code ia.turn_log} (ADR 0014).
 */
public class LlmTimeoutException extends RuntimeException {

	private final String turnId;

	public LlmTimeoutException(String message, String turnId) {
		super(message);
		this.turnId = turnId;
	}

	/** @return identificador del turno, o {@code null} si no se pudo determinar */
	public String turnId() {
		return this.turnId;
	}
}