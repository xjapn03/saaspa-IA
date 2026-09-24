package com.juanp.saaspa.ia.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.juanp.saaspa.ia.TestcontainersConfiguration;

import tools.jackson.databind.json.JsonMapper;

/**
 * Registro durable real sobre PostgreSQL (T1.6): esquema {@code ia} creado por Flyway, aislamiento por
 * tenant (R5), JSON valido y tolerancia a fallos de la base.
 */
@SpringBootTest(properties = "spring.ai.deepseek.api-key=test-key")
@Import(TestcontainersConfiguration.class)
class UsageLoggingTest {

	@Autowired
	private TurnLogService turnLogService;

	@Autowired
	private ToolCallLogger toolCallLogger;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clean() {
		this.jdbcTemplate.update("DELETE FROM ia.turn_log");
		this.jdbcTemplate.update("DELETE FROM ia.tool_call_log");
	}

	@Test
	@DisplayName("registra el turno con tenant, uso y latencia")
	void recordsTurn() {
		UUID turnId = UUID.randomUUID();

		this.turnLogService.record(new TurnLogService.TurnLog(turnId, "kamerinos", "conv-1", "WEB_WIDGET", "CLIENTAS",
				null, null, "customer-agent.v1", "deepseek-flash", 1200, 80, 1500));

		Map<String, Object> row = this.jdbcTemplate.queryForMap("""
				SELECT tenant_id, conversation_id, channel, agent, user_id, role, prompt_version, model,
				       tokens_in, tokens_out, latency_ms
				FROM ia.turn_log WHERE turn_id = ?
				""", turnId);

		assertThat(row).containsEntry("tenant_id", "kamerinos").containsEntry("conversation_id", "conv-1")
				.containsEntry("channel", "WEB_WIDGET").containsEntry("agent", "CLIENTAS")
				.containsEntry("prompt_version", "customer-agent.v1").containsEntry("model", "deepseek-flash")
				.containsEntry("tokens_in", 1200).containsEntry("tokens_out", 80)
				.containsEntry("latency_ms", 1500);
		assertThat(row.get("user_id")).isNull();
		assertThat(row.get("role")).isNull();
	}

	@Test
	@DisplayName("registra la tool call con su estado y su JSON")
	void recordsToolCall() {
		UUID turnId = UUID.randomUUID();

		this.toolCallLogger.record(turnId, "kamerinos", "listarServicios", ToolCallLogger.ToolCallStatus.OK, 120,
				"{\"destacados\":true}", "{\"ok\":true,\"total\":2}");
		this.toolCallLogger.record(turnId, "kamerinos", "consultarDisponibilidad", ToolCallLogger.ToolCallStatus.ERROR,
				40, "{\"servicio\":\"masaje-relajante\"}", "{\"ok\":false,\"message\":\"sin respuesta\"}");

		Map<String, Object> ok = this.jdbcTemplate.queryForMap("""
				SELECT tool_name, status, latency_ms, args_json->>'destacados' AS destacados,
				       result_json->>'total' AS total
				FROM ia.tool_call_log WHERE turn_id = ? AND tool_name = 'listarServicios'
				""", turnId);

		assertThat(ok).containsEntry("status", "OK").containsEntry("latency_ms", 120)
				.containsEntry("destacados", "true").containsEntry("total", "2");
		assertThat(this.jdbcTemplate.queryForObject(
				"SELECT status FROM ia.tool_call_log WHERE turn_id = ? AND tool_name = 'consultarDisponibilidad'",
				String.class, turnId)).isEqualTo("ERROR");
	}

	@Test
	@DisplayName("acota los JSON muy grandes sin romper la columna jsonb")
	void truncatesHugeJson() {
		UUID turnId = UUID.randomUUID();
		String huge = "{\"preview\":\"" + "a".repeat(5000) + "\"}";

		this.toolCallLogger.record(turnId, "kamerinos", "consultarServicio", ToolCallLogger.ToolCallStatus.OK, 10, huge,
				null);

		Map<String, Object> row = this.jdbcTemplate.queryForMap("""
				SELECT args_json->>'truncated' AS truncated, (args_json->>'length')::int AS length, result_json
				FROM ia.tool_call_log WHERE turn_id = ?
				""", turnId);

		assertThat(row).containsEntry("truncated", "true");
		assertThat((Integer) row.get("length")).isGreaterThan(ToolCallLogger.MAX_JSON_LENGTH);
		assertThat(row.get("result_json")).isNull();
	}

	@Test
	@DisplayName("aisla los registros por tenant")
	void isolatesTenants() {
		this.turnLogService.record(new TurnLogService.TurnLog(UUID.randomUUID(), "kamerinos", "conv-1", "WEB_WIDGET",
				"CLIENTAS", null, null, "customer-agent.v1", "deepseek-flash", 10, 5, 100));
		this.turnLogService.record(new TurnLogService.TurnLog(UUID.randomUUID(), "otro-tenant", "conv-9", "WEB_WIDGET",
				"CLIENTAS", null, null, "customer-agent.v1", "deepseek-flash", 20, 6, 200));

		assertThat(countTurns("kamerinos")).isEqualTo(1);
		assertThat(countTurns("otro-tenant")).isEqualTo(1);
	}

	@Test
	@DisplayName("un fallo de la base no interrumpe el turno")
	void loggingFailuresDoNotBreakTheCall() {
		JdbcTemplate broken = mock(JdbcTemplate.class);
		willThrow(new DataAccessResourceFailureException("base caida")).given(broken).update(anyString(),
				any(Object[].class));

		assertThatCode(() -> new TurnLogService(broken).record(new TurnLogService.TurnLog(UUID.randomUUID(),
				"kamerinos", "conv-1", "WEB_WIDGET", "CLIENTAS", null, null, "customer-agent.v1", "model", 1, 1, 1)))
				.doesNotThrowAnyException();
		assertThatCode(() -> new ToolCallLogger(broken, JsonMapper.builder().build()).record(UUID.randomUUID(),
				"kamerinos", "listarServicios", ToolCallLogger.ToolCallStatus.OK, 1, "{}", "{}"))
				.doesNotThrowAnyException();
	}

	private int countTurns(String tenantId) {
		return this.jdbcTemplate.queryForObject("SELECT count(*) FROM ia.turn_log WHERE tenant_id = ?", Integer.class,
				tenantId);
	}
}
