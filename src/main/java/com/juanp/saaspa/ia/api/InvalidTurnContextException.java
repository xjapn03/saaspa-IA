package com.juanp.saaspa.ia.api;

/**
 * El contexto del cuerpo de la peticion no coincide con el turn token (regla R1).
 *
 * <p>La capa de API lo traduce a un {@code ProblemDetail} 400: el turno no es atendible porque el
 * llamante no puede cambiar tenant, canal, agente ni identidad enviando otros valores.
 */
public class InvalidTurnContextException extends RuntimeException {

	public InvalidTurnContextException(String message) {
		super(message);
	}
}
