package com.juanp.saaspa.ia.api;

/**
 * El turno supero el deadline configurado ({@code saaspa.llm.turn-deadline}) esperando al modelo.
 *
 * <p>La capa de API lo traduce a un {@code ProblemDetail} 504.
 */
public class LlmTimeoutException extends RuntimeException {

	public LlmTimeoutException(String message) {
		super(message);
	}
}