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
				prompt_version, model, tokens_in, tokens_out, latency_ms, status)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
					turn.tokensIn(), turn.tokensOut(), turn.latencyMs(), turn.status().name());
		}
		catch (DataAccessException ex) {
			log.error("No se pudo registrar el turno en ia.turn_log: {}", ex.getClass().getSimpleName());
		}
	}

	/**
	 * Estado del turno registrado (ADR 0014).
	 *
	 * <p>{@code OK} si el turno se atendio; {@code DEADLINE} si lo corto el deadline del turno
	 * ({@code saaspa.llm.turn-deadline}) antes de que el modelo respondiera. Antes de ADR 0014 un turno
	 * cortado por el deadline no dejaba fila (A-08).
	 */
	public enum Status {

		/** El turno se atendio (con respuesta del modelo o con handoff en codigo). */
		OK,

		/** El turno se corto al superar {@code saaspa.llm.turn-deadline}. */
		DEADLINE

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
	 */
	public record TurnLog(UUID turnId, String tenantId, String conversationId, String channel, String agent,
			String userId, String role, String promptVersion, String model, int tokensIn, int tokensOut,
			long latencyMs, Status status) {
	}
}
