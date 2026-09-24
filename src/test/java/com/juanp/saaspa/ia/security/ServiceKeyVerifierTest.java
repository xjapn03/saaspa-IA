package com.juanp.saaspa.ia.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verificacion de la clave de servicio de entrada (T1.1).
 */
class ServiceKeyVerifierTest {

	@Test
	@DisplayName("acepta la clave configurada")
	void acceptsConfiguredKey() {
		ServiceKeyVerifier verifier = new ServiceKeyVerifier("clave-de-servicio");

		assertThat(verifier.isConfigured()).isTrue();
		assertThat(verifier.isValid("clave-de-servicio")).isTrue();
	}

	@Test
	@DisplayName("rechaza una clave distinta, vacia o ausente")
	void rejectsOtherKeys() {
		ServiceKeyVerifier verifier = new ServiceKeyVerifier("clave-de-servicio");

		assertThat(verifier.isValid("otra-clave")).isFalse();
		assertThat(verifier.isValid("")).isFalse();
		assertThat(verifier.isValid(null)).isFalse();
		assertThat(verifier.isValid("clave-de-servici")).isFalse();
	}

	@Test
	@DisplayName("sin clave configurada rechaza todo (fallo cerrado)")
	void failsClosedWithoutConfiguredKey() {
		ServiceKeyVerifier verifier = new ServiceKeyVerifier("");

		assertThat(verifier.isConfigured()).isFalse();
		assertThat(verifier.isValid("")).isFalse();
		assertThat(verifier.isValid("cualquier-cosa")).isFalse();
		assertThat(new ServiceKeyVerifier(null).isValid("cualquier-cosa")).isFalse();
	}
}
