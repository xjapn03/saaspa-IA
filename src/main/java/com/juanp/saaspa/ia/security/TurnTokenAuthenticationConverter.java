package com.juanp.saaspa.ia.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Convierte un turn token ya verificado (firma, caducidad, audiencia e issuer) en un
 * {@link TurnTokenAuthentication}.
 *
 * <p>Valida la <strong>forma</strong> de los claims de negocio (obligatorios y valores soportados).
 * Si algo no cuadra lanza {@link BadCredentialsException}, que Spring Security traduce a 401.
 */
public class TurnTokenAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		try {
			TurnToken turnToken = new TurnToken(
					require(jwt, "jti"),
					require(jwt, "tenantId"),
					require(jwt, "conversationId"),
					parseEnum(TurnToken.Channel.class, "channel", require(jwt, "channel")),
					parseEnum(TurnToken.Agent.class, "agent", require(jwt, "agent")),
					optional(jwt, "userId"),
					optionalEnum(TurnToken.Role.class, "role", optional(jwt, "role")),
					jwt.getExpiresAt(),
					jwt.getTokenValue());
			return new TurnTokenAuthentication(turnToken);
		}
		catch (RuntimeException ex) {
			throw new BadCredentialsException("Turn token rechazado: " + ex.getMessage(), ex);
		}
	}

	private static String require(Jwt jwt, String claim) {
		String value = optional(jwt, claim);
		if (value == null) {
			throw new IllegalStateException("falta el claim " + claim);
		}
		return value;
	}

	private static String optional(Jwt jwt, String claim) {
		String value = jwt.getClaimAsString(claim);
		return (value == null || value.isBlank()) ? null : value;
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String claim, String value) {
		try {
			return Enum.valueOf(type, value);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("el claim " + claim + " tiene un valor no soportado: " + value);
		}
	}

	private static <E extends Enum<E>> E optionalEnum(Class<E> type, String claim, String value) {
		return value == null ? null : parseEnum(type, claim, value);
	}
}
