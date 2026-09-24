package com.juanp.saaspa.ia.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Conversion de los claims del turn token ya verificado a la identidad del turno (T1.1).
 */
class TurnTokenAuthenticationConverterTest {

	private final TurnTokenAuthenticationConverter converter = new TurnTokenAuthenticationConverter();

	@Test
	@DisplayName("mapea todos los claims y conserva el token original")
	void mapsAllClaims() {
		Map<String, Object> claims = claims();
		claims.put("channel", "WEB_LOGGED");
		claims.put("agent", "ADMIN");
		claims.put("userId", "user-7");
		claims.put("role", "ADMIN");

		AbstractAuthenticationToken authentication = this.converter.convert(jwt(claims));

		assertThat(authentication.isAuthenticated()).isTrue();
		assertThat(authentication.getCredentials()).isEqualTo("raw-token");
		assertThat(authentication.getPrincipal()).isInstanceOfSatisfying(TurnToken.class, token -> {
			assertThat(token.turnId()).isEqualTo("turn-1");
			assertThat(token.tenantId()).isEqualTo("kamerinos");
			assertThat(token.conversationId()).isEqualTo("conv-1");
			assertThat(token.channel()).isEqualTo(TurnToken.Channel.WEB_LOGGED);
			assertThat(token.agent()).isEqualTo(TurnToken.Agent.ADMIN);
			assertThat(token.userId()).isEqualTo("user-7");
			assertThat(token.role()).isEqualTo(TurnToken.Role.ADMIN);
			assertThat(token.expiresAt()).isNotNull();
			assertThat(token.rawToken()).isEqualTo("raw-token");
			assertThat(token.isAnonymous()).isFalse();
		});
	}

	@Test
	@DisplayName("acepta una visitante anonima sin userId ni role")
	void acceptsAnonymousTurn() {
		TurnToken token = (TurnToken) this.converter.convert(jwt(claims())).getPrincipal();

		assertThat(token.isAnonymous()).isTrue();
		assertThat(token.userId()).isNull();
		assertThat(token.role()).isNull();
	}

	@Test
	@DisplayName("rechaza un token sin claim obligatorio")
	void rejectsMissingRequiredClaim() {
		Map<String, Object> claims = claims();
		claims.remove("tenantId");

		assertThatThrownBy(() -> this.converter.convert(jwt(claims)))
				.isInstanceOf(BadCredentialsException.class)
				.hasMessageContaining("tenantId");
	}

	@Test
	@DisplayName("rechaza un canal no soportado")
	void rejectsUnknownChannel() {
		Map<String, Object> claims = claims();
		claims.put("channel", "TELEGRAM");

		assertThatThrownBy(() -> this.converter.convert(jwt(claims)))
				.isInstanceOf(BadCredentialsException.class)
				.hasMessageContaining("channel");
	}

	@Test
	@DisplayName("rechaza un rol no soportado")
	void rejectsUnknownRole() {
		Map<String, Object> claims = claims();
		claims.put("role", "SUPERADMIN");

		assertThatThrownBy(() -> this.converter.convert(jwt(claims)))
				.isInstanceOf(BadCredentialsException.class)
				.hasMessageContaining("role");
	}

	@Test
	@DisplayName("rechaza un token sin jti")
	void rejectsMissingTurnId() {
		Map<String, Object> claims = claims();
		claims.remove("jti");

		assertThatThrownBy(() -> this.converter.convert(jwt(claims)))
				.isInstanceOf(BadCredentialsException.class)
				.hasMessageContaining("jti");
	}

	private static Jwt jwt(Map<String, Object> claims) {
		return Jwt.withTokenValue("raw-token")
				.header("alg", "ES256")
				.issuer("saaspa-backend")
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(300))
				.claims(registered -> registered.putAll(claims))
				.build();
	}

	private static Map<String, Object> claims() {
		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("jti", "turn-1");
		claims.put("tenantId", "kamerinos");
		claims.put("conversationId", "conv-1");
		claims.put("channel", "WEB_WIDGET");
		claims.put("agent", "CLIENTAS");
		return claims;
	}
}
