package com.juanp.saaspa.ia.eval;

import java.util.List;

/**
 * Resultado de evaluar un caso del dataset (T1.8).
 *
 * @param id identificador del caso
 * @param rule regla cubierta
 * @param passed si el caso cumplio todas las expectativas
 * @param handoffExpected handoff esperado ({@code NONE} o un motivo)
 * @param handoffObserved handoff observado
 * @param missing patrones obligatorios que no aparecieron en la respuesta
 * @param violations patrones prohibidos encontrados en la respuesta
 * @param reply respuesta producida
 */
public record EvalResult(String id, String rule, boolean passed, String handoffExpected, String handoffObserved,
		List<String> missing, List<String> violations, String reply) {
}