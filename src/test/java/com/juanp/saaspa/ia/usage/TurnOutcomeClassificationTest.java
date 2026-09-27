package com.juanp.saaspa.ia.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Base del computo de coste (ADR 0015): {@link TurnCostGuard} cuenta los desenlaces que
 * <em>llamaron al modelo</em>, y ese conjunto tiene que ser una decision explicita de cada valor de
 * {@link TurnLogService.Status}.
 *
 * <p>No necesita Spring ni base de datos: lo que fija es la clasificacion. El {@code switch} exhaustivo
 * de {@link #consumesBudget} hace que anadir un estado nuevo <strong>rompa la compilacion</strong> hasta
 * que alguien decida si gasta presupuesto; es el mecanismo que sustituye a una columna
 * {@code model_called} en {@code ia.turn_log}.
 */
class TurnOutcomeClassificationTest {

	@Test
	@DisplayName("el guard cuenta OK, DEADLINE y ERROR; no cuenta HANDOFF (ADR 0015)")
	void countsOnlyOutcomesThatCalledTheModel() {
		assertThat(Arrays.stream(TurnLogService.Status.values()).filter(TurnOutcomeClassificationTest::consumesBudget))
				.containsExactlyInAnyOrder(TurnLogService.Status.OK, TurnLogService.Status.DEADLINE,
						TurnLogService.Status.ERROR);
	}

	/**
	 * @param status desenlace del turno
	 * @return {@code true} si ese desenlace llego a llamar al modelo y por tanto gasta presupuesto
	 */
	private static boolean consumesBudget(TurnLogService.Status status) {
		return switch (status) {
			case OK, DEADLINE, ERROR -> true;
			case HANDOFF -> false;
		};
	}

}
