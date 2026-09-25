package com.juanp.saaspa.ia.agent.handoff;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Decide cuando hay que pasar la conversacion a una persona (regla R10).
 *
 * <p>La decision es del <strong>codigo</strong>, no del modelo: se revisa el mensaje de la clienta
 * (normalizado, sin acentos ni mayusculas) y se marca el handoff con un motivo. El texto de la
 * respuesta sigue siendo del agente, que ademas ya esta instruido por el prompt para derivar.
 *
 * <p>El sesgo es deliberado: ante una duda de salud se prefiere derivar a una profesional antes que
 * dejar que el agente aconseje. Los reclamos y las peticiones explicitas de hablar con alguien tambien
 * se derivan.
 */
public class HandoffPolicy {

	private static final List<String> EXPLICIT_REQUEST = List.of("hablar con una persona", "hablar con un asesor",
			"hablar con una asesora", "hablar con alguien", "hablar con un humano", "hablar con una humana",
			"hablar con el equipo", "pasame con", "comunicame con", "comunicarme con", "comunicar con",
			"quiero hablar con", "atienda una persona", "me atienda una persona", "con un agente humano",
			"con una persona del equipo");

	private static final List<String> HEALTH_TOPIC = List.of("embaraz", "lactan", "alergi", "medicac", "medicament",
			"antibiotic", "contraindicac", "contraindicad", "cirugia", "operacion", "operada", "operatorio", "diabet",
			"hipertens", "epilep", "cancer", "psorias", "dermatitis", "vitilig", "piel sensible",
			"condicion de la piel", "condiciones de la piel", "infeccion", "herida", "herpes", "estoy tomando",
			"tengo una enfermedad", "problema de salud", "reaccion alergica");

	private static final List<String> COMPLAINT = List.of("reclam", "queja", "inconform", "devoluc", "reembols",
			"cobro indebido", "me cobraron de mas", "cobraron dos veces", "doble cobro", "mal servicio", "pesim",
			"no me atendieron", "no me devolvieron", "estafa", "fraude", "quiero cancelar el pago");

	/**
	 * Evalua el mensaje de la clienta.
	 *
	 * @param userMessage mensaje recibido (puede ser {@code null})
	 * @return decision de handoff; {@code reason} es {@code null} cuando no hay que derivar
	 */
	public Decision evaluate(String userMessage) {
		if (userMessage == null || userMessage.isBlank()) {
			return new Decision(false, null);
		}
		String normalized = normalize(userMessage);
		if (matchesAny(normalized, EXPLICIT_REQUEST)) {
			return new Decision(true, Reason.EXPLICIT_REQUEST);
		}
		if (matchesAny(normalized, HEALTH_TOPIC)) {
			return new Decision(true, Reason.HEALTH_TOPIC);
		}
		if (matchesAny(normalized, COMPLAINT)) {
			return new Decision(true, Reason.COMPLAINT);
		}
		return new Decision(false, null);
	}

	private static boolean matchesAny(String normalized, List<String> patterns) {
		return patterns.stream().anyMatch(normalized::contains);
	}

	private static String normalize(String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFD)
				.replaceAll("\\p{M}", "")
				.toLowerCase(Locale.forLanguageTag("es-CO"));
	}

	/**
	 * Decision de handoff.
	 *
	 * @param requested si hay que pasar la conversacion a una persona
	 * @param reason motivo, si se pidio handoff
	 */
	public record Decision(boolean requested, Reason reason) {
	}

	/** Motivo del handoff. */
	public enum Reason {

		/** Tema de salud: alergias, embarazo, medicacion, piel, reacciones o contraindicaciones. */
		HEALTH_TOPIC,

		/** Reclamo, cobro disputado o devolucion. */
		COMPLAINT,

		/** La persona pidio hablar con alguien del equipo. */
		EXPLICIT_REQUEST

	}
}
