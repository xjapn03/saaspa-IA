package com.juanp.saaspa.ia.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.juanp.saaspa.ia.agent.customer.CustomerAgent;
import com.juanp.saaspa.ia.agent.handoff.HandoffPolicy;
import com.juanp.saaspa.ia.security.TurnToken;

/**
 * Runner del dataset de evaluacion (T1.8): ejecuta cada caso contra el agente CLIENTAS y comprueba
 * sus expectativas.
 *
 * <p>Replica la decision del controlador: primero la politica de handoff (R10) y, si no hay handoff,
 * la llamada al agente. Comprueba el motivo de handoff esperado y que la respuesta no contenga los
 * patrones prohibidos del caso (precios inventados R11/A-12, datos de otras clientas o reportes R3).
 *
 * <p>No llama a un LLM real por si mismo: quien lo invoca decide el modelo. En el build normal se usa
 * un modelo guionizado (R14); la evaluacion con un LLM real corre aparte.
 */
public class CustomerAgentEvaluator {

	private final CustomerAgent customerAgent;

	private final HandoffPolicy handoffPolicy;

	public CustomerAgentEvaluator(CustomerAgent customerAgent, HandoffPolicy handoffPolicy) {
		this.customerAgent = customerAgent;
		this.handoffPolicy = handoffPolicy;
	}

	/**
	 * @param dataset casos a evaluar
	 * @param turnToken identidad del turno usada en todos los casos
	 * @return un resultado por caso
	 */
	public List<EvalResult> run(EvalDataset dataset, TurnToken turnToken) {
		return dataset.cases().stream().map(evalCase -> evaluate(evalCase, turnToken)).toList();
	}

	/**
	 * @param evalCase caso a evaluar
	 * @param turnToken identidad del turno
	 * @return resultado del caso
	 */
	public EvalResult evaluate(EvalCase evalCase, TurnToken turnToken) {
		HandoffPolicy.Decision decision = this.handoffPolicy.evaluate(evalCase.message());
		String observed = decision.requested() ? decision.reason().name() : "NONE";
		String reply = decision.requested()
				? this.handoffPolicy.canonicalReply(decision.reason())
				: this.customerAgent.reply(turnToken, evalCase.message()).text();
		List<String> violations = violations(reply, evalCase.forbid());
		List<String> missing = missing(reply, evalCase.mustMatch());
		boolean passed = observed.equals(evalCase.handoff()) && violations.isEmpty() && missing.isEmpty();
		return new EvalResult(evalCase.id(), evalCase.rule(), passed, evalCase.handoff(), observed, missing, violations,
				reply);
	}

	private static List<String> violations(String reply, List<String> forbid) {
		if (reply == null || forbid.isEmpty()) {
			return List.of();
		}
		List<String> found = new ArrayList<>();
		for (String pattern : forbid) {
			if (Pattern.compile(pattern).matcher(reply).find()) {
				found.add(pattern);
			}
		}
		return List.copyOf(found);
	}

	private static List<String> missing(String reply, List<String> mustMatch) {
		if (reply == null || mustMatch.isEmpty()) {
			return List.copyOf(mustMatch == null ? List.of() : mustMatch);
		}
		List<String> missing = new ArrayList<>();
		for (String pattern : mustMatch) {
			if (!Pattern.compile(pattern).matcher(reply).find()) {
				missing.add(pattern);
			}
		}
		return List.copyOf(missing);
	}
}