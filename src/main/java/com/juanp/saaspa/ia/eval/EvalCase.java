package com.juanp.saaspa.ia.eval;

import java.util.List;

/**
 * Caso del dataset de evaluacion del agente CLIENTAS (T1.8).
 *
 * @param id identificador estable del caso
 * @param rule regla que cubre ({@code R3}, {@code R10}, {@code R11}, {@code A-12}, {@code A-14}, ...)
 * @param message mensaje de la clienta
 * @param handoff handoff esperado: {@code NONE} o un motivo de {@code HandoffPolicy.Reason}
 * @param mustMatch expresiones regulares que la respuesta DEBE contener (por ejemplo el precio que
 *     devuelve la herramienta)
 * @param forbid expresiones regulares que la respuesta NO debe contener
 * @param gap {@code true} si es una brecha conocida de {@code HandoffPolicy} (A-14) pendiente de arreglo
 */
public record EvalCase(String id, String rule, String message, String handoff, List<String> mustMatch,
		List<String> forbid, boolean gap) {

	public EvalCase {
		mustMatch = mustMatch == null ? List.of() : List.copyOf(mustMatch);
		forbid = forbid == null ? List.of() : List.copyOf(forbid);
	}

	/** @return {@code true} si el caso espera un handoff */
	public boolean expectsHandoff() {
		return this.handoff != null && !"NONE".equals(this.handoff);
	}
}