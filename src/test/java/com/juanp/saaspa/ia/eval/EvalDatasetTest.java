package com.juanp.saaspa.ia.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.juanp.saaspa.ia.agent.handoff.HandoffPolicy;

/**
 * Dataset de evaluacion (T1.8): valida el formato y los casos deterministas de handoff (R10). Los
 * falsos positivos/negativos de A-14 se marcan como brecha conocida ({@code gap}) y no se fuerzan en
 * el build: se cierran cuando se arregle el arreglo, momento en el que este test pedira actualizarlos.
 */
class EvalDatasetTest {

	private static final Path DATASET = Path.of("eval", "customer-agent.v1.jsonl");

	private static EvalDataset dataset;

	private final HandoffPolicy policy = new HandoffPolicy();

	@BeforeAll
	static void loadDataset() {
		assertThat(Files.exists(DATASET)).as("existe el dataset %s", DATASET).isTrue();
		dataset = EvalDataset.load(DATASET);
	}

	@Test
	@DisplayName("el dataset tiene al menos 10 casos y todos estan bien formados")
	void datasetIsWellFormed() {
		assertThat(dataset.cases().size()).isGreaterThanOrEqualTo(10);
		assertThat(dataset.cases()).allSatisfy(evalCase -> {
			assertThat(evalCase.id()).isNotBlank();
			assertThat(evalCase.rule()).isNotBlank();
			assertThat(evalCase.message()).isNotBlank();
			assertThat(evalCase.handoff()).isIn("NONE", "HEALTH_TOPIC", "COMPLAINT", "EXPLICIT_REQUEST");
			assertThat(evalCase.forbid())
					.allSatisfy(pattern -> assertThatCode(() -> Pattern.compile(pattern)).doesNotThrowAnyException());
		});
		assertThat(dataset.cases()).extracting(EvalCase::id).doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("cubre R10, R11, R3, A-12 y A-14")
	void datasetCoversTheAgreedRules() {
		assertThat(dataset.cases()).extracting(EvalCase::rule).contains("R10", "R11", "R3", "A-12", "A-14");
	}

	@Test
	@DisplayName("la politica de handoff coincide con lo esperado en los casos sin brecha conocida")
	void handoffMatchesExpectedOnNonGapCases() {
		List<EvalCase> asserted = dataset.cases().stream().filter(evalCase -> !evalCase.gap()).toList();

		assertThat(asserted).isNotEmpty();
		assertThat(asserted).allSatisfy(evalCase -> {
			HandoffPolicy.Decision decision = this.policy.evaluate(evalCase.message());
			String observed = decision.requested() ? decision.reason().name() : "NONE";
			assertThat(observed).as("handoff de %s", evalCase.id()).isEqualTo(evalCase.handoff());
		});
	}

	@Test
	@DisplayName("las brechas conocidas de A-14 siguen documentadas y pendientes")
	void knownGapsAreTracked() {
		List<EvalCase> gaps = dataset.cases().stream().filter(EvalCase::gap).toList();

		assertThat(gaps).isNotEmpty();
		assertThat(gaps).extracting(EvalCase::rule).contains("A-14");
		assertThat(gaps).allSatisfy(evalCase -> {
			HandoffPolicy.Decision decision = this.policy.evaluate(evalCase.message());
			String observed = decision.requested() ? decision.reason().name() : "NONE";
			assertThat(observed).as("brecha conocida %s", evalCase.id()).isNotEqualTo(evalCase.handoff());
		});
	}
}