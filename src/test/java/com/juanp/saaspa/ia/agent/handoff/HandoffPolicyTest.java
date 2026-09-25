package com.juanp.saaspa.ia.agent.handoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Politica de handoff (T1.7): temas de salud, reclamos y peticiones explicitas se derivan; las
 * preguntas normales de catalogo y agenda no.
 */
class HandoffPolicyTest {

	private final HandoffPolicy policy = new HandoffPolicy();

	@ParameterizedTest(name = "tema de salud: {0}")
	@ValueSource(strings = { "Estoy embarazada, puedo hacerme el masaje?", "Soy alérgica a la penicilina y quiero un facial",
			"Estoy tomando medicamentos para la presión", "Tengo psoriasis, me pueden atender?",
			"Me operaron hace poco, hay contraindicaciones?", "Tengo la piel sensible, que me recomiendan?" })
	@DisplayName("deriva los temas de salud a una profesional")
	void handsOffHealthTopics(String message) {
		HandoffPolicy.Decision decision = this.policy.evaluate(message);

		assertThat(decision.requested()).isTrue();
		assertThat(decision.reason()).isEqualTo(HandoffPolicy.Reason.HEALTH_TOPIC);
	}

	@ParameterizedTest(name = "reclamo: {0}")
	@ValueSource(strings = { "Quiero poner un reclamo por el servicio de ayer", "Me cobraron dos veces la cita",
			"Necesito una devolución del abono", "La atención fue pésima" })
	@DisplayName("deriva los reclamos y cobros disputados")
	void handsOffComplaints(String message) {
		HandoffPolicy.Decision decision = this.policy.evaluate(message);

		assertThat(decision.requested()).isTrue();
		assertThat(decision.reason()).isEqualTo(HandoffPolicy.Reason.COMPLAINT);
	}

	@ParameterizedTest(name = "peticion explicita: {0}")
	@ValueSource(strings = { "Quiero hablar con una asesora", "Me pueden comunicar con una persona?",
			"Prefiero hablar con alguien del equipo" })
	@DisplayName("deriva cuando la persona pide hablar con alguien")
	void handsOffExplicitRequests(String message) {
		HandoffPolicy.Decision decision = this.policy.evaluate(message);

		assertThat(decision.requested()).isTrue();
		assertThat(decision.reason()).isEqualTo(HandoffPolicy.Reason.EXPLICIT_REQUEST);
	}

	@Test
	@DisplayName("la peticion explicita tiene prioridad sobre el tema de salud")
	void explicitRequestWins() {
		HandoffPolicy.Decision decision = this.policy
				.evaluate("Soy alérgica y además quiero hablar con una persona del centro");

		assertThat(decision.reason()).isEqualTo(HandoffPolicy.Reason.EXPLICIT_REQUEST);
	}

	@ParameterizedTest(name = "sin handoff: {0}")
	@MethodSource("normalMessages")
	@DisplayName("no deriva las preguntas normales de catalogo y agenda")
	void doesNotHandOffNormalMessages(String message) {
		HandoffPolicy.Decision decision = this.policy.evaluate(message);

		assertThat(decision.requested()).isFalse();
		assertThat(decision.reason()).isNull();
	}

	@Test
	@DisplayName("sin mensaje no deriva")
	void doesNotHandOffEmptyMessages() {
		assertThat(this.policy.evaluate(null).requested()).isFalse();
		assertThat(this.policy.evaluate("   ").requested()).isFalse();
	}

	static Stream<String> normalMessages() {
		return Stream.of("Cuánto cuesta el masaje relajante?", "Qué servicios tienen para el cabello?",
				"Tienen disponibilidad el jueves en la tarde?", "Dónde quedan y hasta qué hora atienden?",
				"Me gustaría agendar una cita");
	}
}
