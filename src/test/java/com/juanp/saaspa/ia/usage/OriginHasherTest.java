package com.juanp.saaspa.ia.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hash del origen del turno (ADR 0020) y politica de su sal (HN-01): determinista para poder contar por
 * cubetas, dependiente de la sal y sin la IP en claro. Ningun test depende de una constante del codigo:
 * todas las sales son explicitas.
 */
class OriginHasherTest {

	/** Sal de prueba explicita (HN-01: ningun test depende de un valor del codigo). */
	private static final String TEST_SALT = "sal-de-prueba-explicita";

	@Test
	@DisplayName("hashea el origen de forma determinista y sin la IP en claro")
	void hashesDeterministically() {
		OriginHasher hasher = new OriginHasher(TEST_SALT);

		String hash = hasher.hash("ip:203.0.113.7");

		assertThat(hash).isEqualTo(hasher.hash("ip:203.0.113.7"))
				.isNotEqualTo(hasher.hash("ip:203.0.113.8"))
				.doesNotContain("203.0.113.7")
				.matches("[0-9a-f]{64}");
	}

	@Test
	@DisplayName("la sal cambia el hash: sin una secreta una IP se revierte por fuerza bruta")
	void saltChangesTheHash() {
		assertThat(new OriginHasher("sal-de-prueba-uno").hash("ip:203.0.113.7"))
				.isNotEqualTo(new OriginHasher("sal-de-prueba-dos").hash("ip:203.0.113.7"));
	}

	@Test
	@DisplayName("un turno sin origen no tiene hash")
	void nullOriginHasNoHash() {
		assertThat(new OriginHasher(TEST_SALT).hash(null)).isNull();
	}

	@Test
	@DisplayName("el constructor rechaza la sal en blanco")
	void constructorRejectsBlankSalt() {
		assertThatThrownBy(() -> new OriginHasher(" ")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("fuera de local no arranca sin sal (HN-01)")
	void createFailsOutsideLocalWithoutSalt() {
		assertThatThrownBy(() -> OriginHasher.create(" ", false))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("IA_COST_GUARD_ORIGIN_SALT");
	}

	@Test
	@DisplayName("fuera de local no arranca con una sal corta (HN-01)")
	void createFailsOutsideLocalWithShortSalt() {
		assertThatThrownBy(() -> OriginHasher.create("sal-corta", false))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("16");
	}

	@Test
	@DisplayName("fuera de local no arranca con la constante legada del repo (HN-01)")
	void createFailsOutsideLocalWithLegacyValue() {
		assertThatThrownBy(() -> OriginHasher.create("saaspa-ia-origin-sin-sal", false))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("legada");
	}

	@Test
	@DisplayName("fuera de local una sal valida arranca y se usa tal cual")
	void createAcceptsAValidSaltOutsideLocal() {
		String hash = OriginHasher.create(TEST_SALT, false).hash("ip:203.0.113.7");

		assertThat(hash).isEqualTo(new OriginHasher(TEST_SALT).hash("ip:203.0.113.7"));
	}

	@Test
	@DisplayName("en local sin sal se genera una aleatoria por proceso, estable dentro de el")
	void localWithoutSaltGeneratesARandomPerProcessSalt() {
		OriginHasher first = OriginHasher.create(null, true);
		OriginHasher second = OriginHasher.create(null, true);

		assertThat(first.hash("ip:203.0.113.7")).isEqualTo(first.hash("ip:203.0.113.7")).hasSize(64);
		assertThat(first.hash("ip:203.0.113.7")).isNotEqualTo(second.hash("ip:203.0.113.7"));
	}

	@Test
	@DisplayName("en local con sal configurada la usa")
	void localWithConfiguredSaltUsesIt() {
		String hash = OriginHasher.create(TEST_SALT, true).hash("ip:203.0.113.7");

		assertThat(hash).isEqualTo(new OriginHasher(TEST_SALT).hash("ip:203.0.113.7"));
	}

}
