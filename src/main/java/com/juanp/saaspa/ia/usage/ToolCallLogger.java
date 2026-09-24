package com.juanp.saaspa.ia.usage;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Auditoria de tool calls en {@code ia.tool_call_log} (tabla creada por Flyway V1).
 *
 * <p>Guarda nombre de la herramienta, estado, latencia y los argumentos y el resultado en JSON.
 * Los textos se acotan: si superan {@value #MAX_JSON_LENGTH} caracteres se guarda un objeto JSON
 * valido con {@code truncated}, {@code length} y un extracto, para no romper la columna {@code jsonb}
 * ni almacenar respuestas enormes (regla R8: tampoco se vuelca el mensaje de la persona).
 *
 * <p>Si la escritura falla, se registra el error (solo el tipo) y la conversacion continua: la
 * auditoria no puede tumbar un turno.
 */
public class ToolCallLogger {

	private static final Logger log = LoggerFactory.getLogger(ToolCallLogger.class);

	static final int MAX_JSON_LENGTH = 4000;

	private static final String INSERT_CALL = """
			INSERT INTO ia.tool_call_log (turn_id, tenant_id, tool_name, status, latency_ms, args_json, result_json)
			VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
			""";

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	public ToolCallLogger(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	/**
	 * Registra una tool call.
	 *
	 * @param turnId turno al que pertenece
	 * @param tenantId tenant del turno
	 * @param toolName nombre de la herramienta
	 * @param status resultado del tool call
	 * @param latencyMs latencia en milisegundos
	 * @param argsJson argumentos recibidos (JSON)
	 * @param resultJson resultado devuelto (JSON)
	 */
	public void record(UUID turnId, String tenantId, String toolName, ToolCallStatus status, long latencyMs,
			String argsJson, String resultJson) {
		try {
			this.jdbcTemplate.update(INSERT_CALL, turnId, tenantId, toolName, status.name(), latencyMs,
					jsonOrNull(argsJson), jsonOrNull(resultJson));
		}
		catch (DataAccessException ex) {
			log.error("No se pudo registrar el tool call en ia.tool_call_log: {}", ex.getClass().getSimpleName());
		}
	}

	private String jsonOrNull(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		if (json.length() <= MAX_JSON_LENGTH) {
			return json;
		}
		ObjectNode truncated = this.objectMapper.createObjectNode();
		truncated.put("truncated", true);
		truncated.put("length", json.length());
		truncated.put("preview", json.substring(0, MAX_JSON_LENGTH));
		return this.objectMapper.writeValueAsString(truncated);
	}

	/** Estado de una tool call. */
	public enum ToolCallStatus {

		/** La herramienta respondio con datos. */
		OK,

		/** La herramienta no pudo responder (backend caido, error o datos invalidos). */
		ERROR

	}
}
