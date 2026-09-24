package com.juanp.saaspa.ia.api;

/**
 * El turno pide un agente que esta fase aun no implementa (Fase 1: solo CLIENTAS).
 *
 * <p>La capa de API lo traduce a 501 para que NestJS sepa que la peticion es legitima pero no
 * atendible todavia (el agente ADMIN llega en la Fase 3).
 */
public class UnsupportedAgentException extends RuntimeException {

	public UnsupportedAgentException(String message) {
		super(message);
	}
}
