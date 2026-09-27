package com.juanp.saaspa.ia.usage;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Registro durable de turnos en {@code ia.turn_log} (tabla creada por Flyway V1).
 *
 * <p>Guarda lo necesario para trazabilidad y costos: tenant, conversacion, canal, agente, identidad
 * (id y rol, nunca datos personales), version del prompt, modelo, tokens y latencia (R5: todo lleva
 * {@code tenant_id}).
 *
 * <p>La trazabilidad no debe tumbar el turno: si la escritura falla se registra el error (solo el
 * tipo de excepcion, regla R8) y la respuesta continua.
 */
public class TurnLogService {

	private static final Logger log = LoggerFactory.getLogger(TurnLogService.class);

	private static final String INSERT_TURN = """
			INSERT INTO ia.turn_log (turn_id, tenant_id, conversation_id, channel, agent, user_id, role,
				prompt_version, model, tokens_in, tokens_out, latency_ms, status, handoff_reason, error_code,
				origin_hash)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private final JdbcTemplate jdbcTemplate;

	public TurnLogService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Registra un turno atendido.
	 *
	 * @param turn datos del turno
	 */
	public void record(TurnLog turn) {
		try {
			this.jdbcTemplate.update(INSERT_TURN, turn.turnId(), turn.tenantId(), turn.conversationId(),
					turn.channel(), turn.agent(), turn.userId(), turn.role(), turn.promptVersion(), turn.model(),
					turn.tokensIn(), turn.tokensOut(), turn.latencyMs(), turn.status().name(), turn.handoffReason(),
					turn.errorCode(), turn.originHash());
		}
		catch (DataAccessException ex) {
			log.error("No se pudo registrar el turno en ia.turn_log: {}", ex.getClass().getSimpleName());
		}
	}

	/**
	 * Estado del turno registrado (ADR 0014 y ADR 0015).
	 *
	 * <p>{@code OK} si el turno se atendio; {@code HANDOFF} si lo derivo la politica de handoff (no se
	 * llamo al modelo); {@code DEADLINE} si lo corto el deadline del turno
	 * ({@code saaspa.llm.turn-deadline}) antes de que el modelo respondiera; {@code ERROR} si el modelo
	 * llego a llamarse y el turno fallo despues (backend, proveedor o fallo inesperado).
	 *
	 * <p><strong>Base del computo de coste (ADR 0015):</strong> {@link TurnCostGuard} cuenta los
	 * desenlaces que <em>llamaron al modelo</em>, que son todos menos {@code HANDOFF}. Anadir un estado
	 * nuevo obliga a decidir si gasta presupuesto: {@code TurnOutcomeClassificationTest} tiene un
	 * {@code switch} exhaustivo que deja de compilar hasta que se decida.
	 */
	public enum Status {

		/** El turno se atendio con una respuesta del modelo. */
		OK,

		/** El turno se derivo a una persona: no se llamo al modelo y no se gastaron tokens. */
		HANDOFF,

		/** El turno se corto al superar {@code saaspa.llm.turn-deadline}. */
		DEADLINE,

		/** El turno llamo al modelo y fallo despues: los tokens gastados se desconocen (se registran en 0). */
		ERROR

	}

	/**
	 * Tipo de fallo de un turno con {@code status = ERROR} (ADR 0015).
	 *
	 * <p>Es un conjunto <strong>cerrado</strong> de codigos mapeados en codigo a partir de la excepcion,
	 * nunca su mensaje ni el nombre crudo de la clase: asi el registro es estable y consultable y no
	 * filtra detalles internos del proveedor (R8).
	 */
	public enum ErrorCode {

		/** El backend de agenda no respondio (timeout, conexion rechazada o error de red). */
		BACKEND_UNAVAILABLE,

		/** El backend de agenda respondio, pero con un error. */
		BACKEND_ERROR,

		/**
		 * El fallo vino del camino del modelo (proveedor tras los reintentos, argumentos de herramienta
		 * o cualquier otro fallo dentro de la llamada al agente).
		 */
		MODEL_ERROR

	}

	/**
	 * Turno registrado en {@code ia.turn_log}.
	 *
	 * @param turnId identificador del turno ({@code jti} del turn token)
	 * @param tenantId tenant que atendio la peticion
	 * @param conversationId conversacion dentro del tenant
	 * @param channel canal de origen
	 * @param agent agente que atendio
	 * @param userId usuario autenticado, si lo hay
	 * @param role rol del usuario, si lo hay
	 * @param promptVersion version del prompt usada
	 * @param model modelo que respondio
	 * @param tokensIn tokens de entrada
	 * @param tokensOut tokens de salida
	 * @param latencyMs latencia del turno en milisegundos
	 * @param status estado del turno ({@link Status})
	 * @param handoffReason motivo del handoff cuando {@code status = HANDOFF}
	 * ({@code HEALTH_TOPIC} | {@code COMPLAINT} | {@code EXPLICIT_REQUEST}); {@code null} en el resto
	 * @param errorCode tipo de fallo ({@link ErrorCode}) cuando {@code status = ERROR}; {@code null} en el
	 * resto
	 * @param originHash hash del origen del turno (ADR 0020) con el que se cuentan los turnos por origen;
	 * {@code null} si el turno no trae origen (username/IP) todavia
	 */
	public record TurnLog(UUID turnId, String tenantId, String conversationId, String channel, String agent,
			String userId, String role, String promptVersion, String model, int tokensIn, int tokensOut,
			long latencyMs, Status status, String handoffReason, String errorCode, String originHash) {
	}
}
