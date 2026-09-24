package com.juanp.saaspa.ia.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Respuesta de un turno ({@code POST /api/v1/chat}, contrato chat-api).
 *
 * <p>El texto de {@code reply} es lo unico que se le muestra a la persona; los enlaces y el handoff
 * son decisiones del codigo, no del modelo.
 *
 * @param turnId mismo identificador que envio NestJS
 * @param reply respuesta del agente
 * @param handoff si hay que derivar a una persona
 * @param usage modelo y tokens del turno (para costos y registro)
 * @param sources fuentes usadas (RAG: vacio en la Fase 1)
 */
public record ChatResponseDto(UUID turnId, Reply reply, Handoff handoff, Usage usage, List<Source> sources) {

	/**
	 * Respuesta del agente.
	 *
	 * @param text texto para la persona
	 * @param links enlaces pre-diligenciados (Fase 2; vacio en la Fase 1)
	 */
	public record Reply(String text, List<Link> links) {
	}

	/**
	 * Enlace sugerido.
	 *
	 * @param label texto del enlace
	 * @param url destino
	 */
	public record Link(String label, String url) {
	}

	/**
	 * Decision de handoff.
	 *
	 * @param requested si hay que derivar a una persona
	 * @param reason motivo (por ejemplo un tema sensible)
	 */
	public record Handoff(boolean requested, String reason) {
	}

	/**
	 * Uso del modelo en el turno.
	 *
	 * @param model modelo que respondio
	 * @param tokensIn tokens de entrada
	 * @param tokensOut tokens de salida
	 */
	public record Usage(String model, Integer tokensIn, Integer tokensOut) {
	}

	/**
	 * Fuente documental (Fase 4).
	 *
	 * @param title titulo del documento
	 * @param ref referencia interna
	 */
	public record Source(String title, String ref) {
	}
}
