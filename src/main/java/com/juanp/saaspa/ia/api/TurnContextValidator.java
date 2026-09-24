package com.juanp.saaspa.ia.api;

import java.util.Objects;

import com.juanp.saaspa.ia.api.dto.ChatRequestDto;
import com.juanp.saaspa.ia.security.TurnToken;

/**
 * Contrasta el contexto del cuerpo de la peticion con los claims del turn token ya verificado.
 *
 * <p>Regla R1: el token manda. Si el cuerpo dice otra cosa (otro tenant, otra conversacion, otro canal,
 * otro agente u otra identidad), se rechaza el turno; asi un error de NestJS o un cuerpo manipulado no
 * puede cambiar a quien se le responde ni con que identidad se consulta la API interna.
 */
public final class TurnContextValidator {

	private TurnContextValidator() {
	}

	/**
	 * @param turnToken identidad verificada del turno
	 * @param request cuerpo recibido
	 * @throws InvalidTurnContextException si el cuerpo no coincide con el token
	 */
	public static void validate(TurnToken turnToken, ChatRequestDto request) {
		match("turnId", turnToken.turnId(), request.turnId() == null ? null : request.turnId().toString());
		match("tenantId", turnToken.tenantId(), request.tenantId());
		match("conversationId", turnToken.conversationId(), request.conversationId());
		match("channel", name(turnToken.channel()), name(request.channel()));
		match("agent", name(turnToken.agent()), name(request.agent()));

		ChatRequestDto.Identity identity = request.identity();
		if (identity == null) {
			throw new InvalidTurnContextException("Falta la identidad del turno");
		}
		boolean tokenHasUser = turnToken.userId() != null;
		boolean bodyHasUser = identity.userId() != null;
		if (tokenHasUser != bodyHasUser) {
			throw new InvalidTurnContextException("La identidad del cuerpo no coincide con el turn token");
		}
		if (tokenHasUser) {
			match("identity.userId", turnToken.userId(), identity.userId());
			match("identity.role", name(turnToken.role()), name(identity.role()));
			if (identity.kind() != ChatRequestDto.IdentityKind.USER) {
				throw new InvalidTurnContextException("La identidad del cuerpo no coincide con el turn token");
			}
		}
		else if (identity.kind() != ChatRequestDto.IdentityKind.ANONYMOUS) {
			throw new InvalidTurnContextException("La identidad del cuerpo no coincide con el turn token");
		}
	}

	private static String name(Enum<?> value) {
		return value == null ? null : value.name();
	}

	private static void match(String field, String expected, String actual) {
		if (!Objects.equals(expected, actual)) {
			throw new InvalidTurnContextException("El campo " + field + " del cuerpo no coincide con el turn token");
		}
	}
}
