package com.juanp.saaspa.ia.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Utilidades de test: par de claves EC P-256 y firma de turn tokens tal como los emitira NestJS
 * (ES256 con {@code kid} en la cabecera). Se generan en memoria: no hay material de clave en el
 * repositorio.
 */
final class TestTurnTokens {

	private TestTurnTokens() {
	}

	static KeyPair generateKeyPair() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
			generator.initialize(new ECGenParameterSpec("secp256r1"));
			return generator.generateKeyPair();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Clave publica en PEM (formato SubjectPublicKeyInfo). */
	static String pem(PublicKey publicKey) {
		return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(publicKey.getEncoded())
				+ "\n-----END PUBLIC KEY-----";
	}

	/** Clave publica en base64 del PEM (una sola linea, apta para variables de entorno). */
	static String base64Pem(PublicKey publicKey) {
		return Base64.getEncoder().encodeToString(pem(publicKey).getBytes(StandardCharsets.UTF_8));
	}

	/** Claims de un turno valido de widget web anonimo. */
	static Map<String, Object> defaultClaims() {
		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("iss", "saaspa-backend");
		claims.put("aud", List.of("saaspa-ia"));
		claims.put("jti", "turn-1");
		claims.put("tenantId", "kamerinos");
		claims.put("conversationId", "conv-1");
		claims.put("channel", "WEB_WIDGET");
		claims.put("agent", "CLIENTAS");
		return claims;
	}

	static String signValid(KeyPair keyPair, String kid, Map<String, Object> claims) {
		return sign(keyPair.getPrivate(), kid, Instant.now().plusSeconds(300), claims);
	}

	static String sign(PrivateKey privateKey, String kid, Instant expiresAt, Map<String, Object> claims) {
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(kid).build(),
					claims(claims, expiresAt));
			jwt.sign(new ECDSASigner((ECPrivateKey) privateKey));
			return jwt.serialize();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Token firmado con HS256 (ataque de confusion de algoritmo). */
	static String signHs256(Map<String, Object> claims, String secret) {
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
					claims(claims, Instant.now().plusSeconds(300)));
			jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
			return jwt.serialize();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static JWTClaimsSet claims(Map<String, Object> claims, Instant expiresAt) {
		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
		claims.forEach(builder::claim);
		builder.issueTime(Date.from(Instant.now()));
		builder.expirationTime(Date.from(expiresAt));
		return builder.build();
	}
}
