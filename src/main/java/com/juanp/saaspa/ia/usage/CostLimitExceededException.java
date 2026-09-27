package com.juanp.saaspa.ia.usage;

import java.time.Duration;

/**
 * El turno supero uno de los topes de coste (ADR 0010): turnos o tokens por ventana, por tenant o por
 * conversacion. La capa de API lo traduce a un {@code ProblemDetail} 429.
 *
 * <p>El limite se evalua contra {@code ia.turn_log} (fuente de verdad), nunca contra un contador en
 * memoria, asi que un reinicio no borra el consumo acumulado.
 */
public class CostLimitExceededException extends RuntimeException {

	/** Ambito cuyo tope se supero. */
	public enum Scope {

		/** Tope global del tenant. */
		TENANT,

		/** Tope de la conversacion. */
		CONVERSATION

	}

	/** Medida que supero el tope. */
	public enum Measure {

		/** Turnos registrados en la ventana. */
		TURNS,

		/** Tokens (entrada + salida) registrados en la ventana. */
		TOKENS

	}

	private final Scope scope;

	private final Measure measure;

	private final long limit;

	private final long measured;

	private final Duration window;

	public CostLimitExceededException(Scope scope, Measure measure, long limit, long measured, Duration window) {
		super("Tope de coste superado: %s %s (%d de %d en %s)".formatted(scope, measure, measured, limit, window));
		this.scope = scope;
		this.measure = measure;
		this.limit = limit;
		this.measured = measured;
		this.window = window;
	}

	/** @return ambito del tope superado */
	public Scope scope() {
		return this.scope;
	}

	/** @return medida que supero el tope */
	public Measure measure() {
		return this.measure;
	}

	/** @return valor configurado del tope */
	public long limit() {
		return this.limit;
	}

	/** @return valor medido en la ventana */
	public long measured() {
		return this.measured;
	}

	/** @return ventana de medida */
	public Duration window() {
		return this.window;
	}
}
