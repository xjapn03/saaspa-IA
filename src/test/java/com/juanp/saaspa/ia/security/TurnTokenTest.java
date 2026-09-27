package com.juanp.saaspa.ia.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Clave de origen del turno (ADR 0020, hallazgo H-04): el usuario manda cuando lo hay y la IP resuelta por
 * el backend cuando el turno es anonimo. Es la clave del tope que impide que una sola IP agote el
 * presupuesto del tenant, asi que no puede ser rotable (a diferencia del conversationId).
 */
class TurnTokenTest {

	@Test
	@DisplayName("un turno logueado se cuenta por usuario, aunque el token traiga IP")
	void loggedTurnOriginatesFromTheUser() {
		assertThat(token("user-7", "203.0.113.7").origin()).isEqualTo("user:user-7");
	}

	@Test
	@DisplayName("un turno anonimo se cuenta por la IP que resolvio el backend")
	void anonymousTurnOriginatesFromTheIp() {
		assertThat(token(null, "203.0.113.7").origin()).isEqualTo("ip:203.0.113.7");
	}

	@Test
	@DisplayName("sin usuario ni claim de IP el turno no tiene origen (tolerancia de ADR 0020)")
	void turnWithoutOrigin() {
		assertThat(token(null, null).origin()).isNull();
	}

	private static TurnToken token(String userId, String clientIp) {
		return new TurnToken("turn-1", "kamerinos", "conv-1", TurnToken.Channel.WEB_WIDGET,
				TurnToken.Agent.CLIENTAS, userId, null, clientIp, Instant.now().plusSeconds(300), "raw-token");
	}

}
