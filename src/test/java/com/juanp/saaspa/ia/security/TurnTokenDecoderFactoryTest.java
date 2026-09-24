package com.juanp.saaspa.ia.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import com.juanp.saaspa.ia.config.TurnTokenProperties;
import com.juanp.saaspa.ia.config.TurnTokenProperties.TurnTokenKey;

/**
 * Verificacion del turn token (T1.1): firma ES256 con clave publica EC seleccionada por {@code kid},
 * audiencia, issuer opcional y caducidad.
 */
class TurnTokenDecoderFactoryTest {

	private static final String KID_CURRENT = "kid-current";

	private static final String KID_PREVIOUS = "kid-previous";

	private static final String SERVICE_AUDIENCE = "saaspa-ia";

	private final KeyPair currentKey = TestTurnTokens.generateKeyPair();

	private final KeyPair previousKey = TestTurnTokens.generateKeyPair();

	private final KeyPair rogueKey = TestTurnTokens.generateKeyPair();

	@Test
	@DisplayName("acepta un turn token firmado con la clave vigente")
	void acceptsTokenSignedWithCurrentKey() {
		Jwt jwt = decoder("")
				.decode(TestTurnTokens.signValid(this.currentKey, KID_CURRENT, TestTurnTokens.defaultClaims()));

		assertThat(jwt.getClaimAsString("tenantId")).isEqualTo("kamerinos");
		assertThat(jwt.getClaimAsString("conversationId")).isEqualTo("conv-1");
		assertThat(jwt.getId()).isEqualTo("turn-1");
	}

	@Test
	@DisplayName("acepta la segunda clave (rotacion) configurada como PEM en crudo")
	void acceptsPreviousKeyForRotation() {
		Jwt jwt = decoder("")
				.decode(TestTurnTokens.signValid(this.previousKey, KID_PREVIOUS, TestTurnTokens.defaultClaims()));

		assertThat(jwt.getClaimAsString("channel")).isEqualTo("WEB_WIDGET");
	}

	@Test
	@DisplayName("rechaza un turn token caducado")
	void rejectsExpiredToken() {
		String token = TestTurnTokens.sign(this.currentKey.getPrivate(), KID_CURRENT, Instant.now().minusSeconds(60),
				TestTurnTokens.defaultClaims());

		assertThatThrownBy(() -> decoder("").decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	@DisplayName("rechaza un turn token de otra audiencia")
	void rejectsWrongAudience() {
		String token = TestTurnTokens.signValid(this.currentKey, KID_CURRENT,
				claimsWithAudience("otro-servicio"));

		assertThatThrownBy(() -> decoder("").decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	@DisplayName("rechaza un kid no configurado")
	void rejectsUnknownKid() {
		String token = TestTurnTokens.signValid(this.rogueKey, "kid-rogue", TestTurnTokens.defaultClaims());

		assertThatThrownBy(() -> decoder("").decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	@DisplayName("rechaza un token HS256 (confusion de algoritmo)")
	void rejectsHs256Token() {
		String secret = TestTurnTokens.base64Pem(this.currentKey.getPublic());
		String token = TestTurnTokens.signHs256(TestTurnTokens.defaultClaims(), secret);

		assertThatThrownBy(() -> decoder("").decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	@DisplayName("valida el issuer solo cuando esta configurado")
	void validatesIssuerWhenConfigured() {
		String token = TestTurnTokens.signValid(this.currentKey, KID_CURRENT, TestTurnTokens.defaultClaims());

		assertThat(decoder("saaspa-backend").decode(token).getClaimAsString("iss")).isEqualTo("saaspa-backend");
		assertThatThrownBy(() -> decoder("otro-backend").decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	@DisplayName("sin claves configuradas rechaza todo (fallo cerrado)")
	void failsClosedWithoutKeys() {
		JwtDecoder decoder = TurnTokenDecoderFactory
				.create(new TurnTokenProperties(SERVICE_AUDIENCE, "", List.of(new TurnTokenKey("", ""))));
		String token = TestTurnTokens.signValid(this.currentKey, KID_CURRENT, TestTurnTokens.defaultClaims());

		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
	}

	private JwtDecoder decoder(String issuer) {
		TurnTokenProperties properties = new TurnTokenProperties(SERVICE_AUDIENCE, issuer,
				List.of(new TurnTokenKey(KID_CURRENT, TestTurnTokens.base64Pem(this.currentKey.getPublic())),
						new TurnTokenKey(KID_PREVIOUS, TestTurnTokens.pem(this.previousKey.getPublic())),
						new TurnTokenKey("", "")));
		return TurnTokenDecoderFactory.create(properties);
	}

	private static Map<String, Object> claimsWithAudience(String audience) {
		Map<String, Object> claims = TestTurnTokens.defaultClaims();
		claims.put("aud", List.of(audience));
		return claims;
	}
}
