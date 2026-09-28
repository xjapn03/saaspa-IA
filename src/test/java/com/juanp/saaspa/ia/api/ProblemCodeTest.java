package com.juanp.saaspa.ia.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guarda del catalogo de errores (ADR 0017): el {@code detail} de un {@code ProblemDetail} es el texto que
 * NestJS reenvia al widget, asi que ninguno puede llevar jerga interna, referencias al roadmap ni
 * identificadores. Este test es lo que impide que vuelva a colarse un mensaje de diagnostico hacia la clienta.
 */
class ProblemCodeTest {

	/** Terminos que solo tienen sentido dentro del sistema (o del roadmap) y que no debe leer una clienta. */
	private static final List<String> INTERNAL_TERMS = List.of("token", "tenant", "conversation", "exception",
			"fase", "stack", "null", "scope", "measure", "backend", "jwt", "es256", "hash", "http", "api",
			"turnId", "servicio de ia");

	@Test
	@DisplayName("los textos publicos solo llevan letras, espacios y puntuacion basica")
	void publicTextsArePlainSentences() {
		assertThat(ProblemCode.values()).allSatisfy(code -> {
			assertThat(code.title()).as("title de %s", code).matches("^[\\p{L} ,.;:]+$");
			assertThat(code.publicDetail()).as("detail de %s", code).matches("^[\\p{L} ,.;:]+$");
		});
	}

	@Test
	@DisplayName("ningun texto publico (title ni detail) lleva jerga interna ni el roadmap (R8 / ADR 0017)")
	void publicTextsDoNotLeakInternals() {
		String[] terms = INTERNAL_TERMS.toArray(String[]::new);
		assertThat(ProblemCode.values()).allSatisfy(code -> {
			assertThat(code.title()).as("title de %s", code).doesNotContainIgnoringCase(terms);
			assertThat(code.publicDetail()).as("detail de %s", code).doesNotContainIgnoringCase(terms);
		});
	}

	@Test
	@DisplayName("cada codigo agrupa un estado de error y textos no vacios")
	void codesAreUsable() {
		assertThat(ProblemCode.values()).allSatisfy(code -> {
			assertThat(code.status().isError()).as("estado de %s", code).isTrue();
			assertThat(code.title()).as("title de %s", code).isNotBlank();
			assertThat(code.publicDetail()).as("detail de %s", code).isNotBlank();
		});
	}

	@Test
	@DisplayName("los dos 502 y los dos 400 existen con codigos distintos (diagnostico sin prosa)")
	void codesDistinguishTheCases() {
		assertThat(ProblemCode.INVALID_BODY.publicDetail()).isEqualTo(ProblemCode.MALFORMED_BODY.publicDetail());
		assertThat(ProblemCode.INVALID_BODY.status()).isEqualTo(ProblemCode.MALFORMED_BODY.status());
		assertThat(ProblemCode.BACKEND_ERROR.name()).isNotEqualTo(ProblemCode.BACKEND_UNAVAILABLE.name());
	}

}
