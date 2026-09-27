package com.juanp.saaspa.ia.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hash del origen del turno (ADR 0020): determinista para poder contar por cubetas, dependiente de la sal y
 * sin la IP en claro.
 */
class OriginHasherTest {

	@Test
	@DisplayName("hashea el origen de forma determinista y sin la IP en claro")
	void hashesDeterministically() {
		OriginHasher hasher = new OriginHasher("sal-de-test");

		String hash = hasher.hash("ip:203.0.113.7");

		assertThat(hash).isEqualTo(hasher.hash("ip:203.0.113.7"))
				.isNotEqualTo(hasher.hash("ip:203.0.113.8"))
				.doesNotContain("203.0.113.7")
				.matches("[0-9a-f]{64}");
	}

	@Test
	@DisplayName("la sal cambia el hash: sin ella una IP se revierte por fuerza bruta")
	void saltChangesTheHash() {
		assertThat(new OriginHasher("sal-a").hash("ip:203.0.113.7"))
				.isNotEqualTo(new OriginHasher("sal-b").hash("ip:203.0.113.7"));
	}

	@Test
	@DisplayName("un turno sin origen no tiene hash")
	void nullOriginHasNoHash() {
		assertThat(new OriginHasher("sal-de-test").hash(null)).isNull();
	}

	@Test
	@DisplayName("sin sal configurada el tope por origen sigue funcionando (se avisa al arrancar)")
	void worksWithoutSalt() {
		OriginHasher hasher = new OriginHasher("");

		assertThatCode(() -> hasher.hash("ip:203.0.113.7")).doesNotThrowAnyException();
		assertThat(hasher.hash("ip:203.0.113.7")).hasSize(64);
	}

}
