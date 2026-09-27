package com.juanp.saaspa.ia.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.juanp.saaspa.ia.TestcontainersConfiguration;
import com.juanp.saaspa.ia.usage.CostLimitExceededException.Measure;
import com.juanp.saaspa.ia.usage.CostLimitExceededException.Scope;

/**
 * Tope de coste por tenant y por conversacion (ADR 0010) contra PostgreSQL real: el consumo se mide con una
 * consulta agregada sobre {@code ia.turn_log}, asi que lo que hay que verificar de verdad es la ventana y
 * los cuatro topes, no un contador simulado.
 *
 * <p>Los topes del test son pequenos a proposito para poder superarlos con pocas filas.
 */
@SpringBootTest(properties = { "spring.ai.deepseek.api-key=test-key", "saaspa.cost-guard.window=1h",
		"saaspa.cost-guard.tenant-max-turns=5", "saaspa.cost-guard.tenant-max-tokens=10000",
		"saaspa.cost-guard.conversation-max-turns=3", "saaspa.cost-guard.conversation-max-tokens=5000" })
@Import(TestcontainersConfiguration.class)
class TurnCostGuardTest {

	private static final String INSERT_TURN = """
			INSERT INTO ia.turn_log (turn_id, tenant_id, conversation_id, channel, agent, prompt_version, model,
				tokens_in, tokens_out, latency_ms, status, created_at)
			VALUES (?, ?, ?, 'WEB_WIDGET', 'CLIENTAS', 'customer-agent.v2', 'deepseek-flash', ?, 0, 100, 'OK', ?)
			""";

	@Autowired
	private TurnCostGuard turnCostGuard;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clean() {
		this.jdbcTemplate.update("DELETE FROM ia.turn_log");
	}

	@Test
	@DisplayName("deja pasar un turno por debajo de los topes")
	void allowsTurnsUnderTheLimits() {
		insertTurn("kamerinos", "conv-1", 1000, Duration.ofMinutes(5));

		assertThatCode(() -> this.turnCostGuard.check("kamerinos", "conv-1")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("corta por el tope de turnos de la conversacion")
	void rejectsTurnsAtTheConversationLimit() {
		insertTurn("kamerinos", "conv-1", 10, Duration.ofMinutes(5));
		insertTurn("kamerinos", "conv-1", 10, Duration.ofMinutes(4));
		insertTurn("kamerinos", "conv-1", 10, Duration.ofMinutes(3));

		assertThatThrownBy(() -> this.turnCostGuard.check("kamerinos", "conv-1"))
				.isInstanceOf(CostLimitExceededException.class)
				.satisfies(exception -> {
					CostLimitExceededException cost = (CostLimitExceededException) exception;
					assertThat(cost.scope()).isEqualTo(Scope.CONVERSATION);
					assertThat(cost.measure()).isEqualTo(Measure.TURNS);
					assertThat(cost.limit()).isEqualTo(3);
					assertThat(cost.measured()).isEqualTo(3);
				});
	}

	@Test
	@DisplayName("corta por el tope de tokens de la conversacion")
	void rejectsTurnsAtTheConversationTokenLimit() {
		insertTurn("kamerinos", "conv-1", 3000, Duration.ofMinutes(5));
		insertTurn("kamerinos", "conv-1", 2500, Duration.ofMinutes(4));

		assertThatThrownBy(() -> this.turnCostGuard.check("kamerinos", "conv-1"))
				.isInstanceOf(CostLimitExceededException.class)
				.satisfies(exception -> {
					CostLimitExceededException cost = (CostLimitExceededException) exception;
					assertThat(cost.scope()).isEqualTo(Scope.CONVERSATION);
					assertThat(cost.measure()).isEqualTo(Measure.TOKENS);
					assertThat(cost.measured()).isEqualTo(5500);
				});
	}


	@Test
	@DisplayName("corta por el tope de turnos del tenant aunque la conversacion sea nueva")
	void rejectsTurnsAtTheTenantLimitAcrossConversations() {
		for (int i = 0; i < 5; i++) {
			insertTurn("kamerinos", "conv-" + i, 1, Duration.ofMinutes(5));
		}

		assertThatThrownBy(() -> this.turnCostGuard.check("kamerinos", "conv-nueva"))
				.isInstanceOf(CostLimitExceededException.class)
				.satisfies(exception -> {
					CostLimitExceededException cost = (CostLimitExceededException) exception;
					assertThat(cost.scope()).isEqualTo(Scope.TENANT);
					assertThat(cost.measure()).isEqualTo(Measure.TURNS);
					assertThat(cost.measured()).isEqualTo(5);
				});
	}

	@Test
	@DisplayName("corta por el tope de tokens del tenant")
	void rejectsTurnsAtTheTenantTokenLimit() {
		insertTurn("kamerinos", "conv-1", 6000, Duration.ofMinutes(5));
		insertTurn("kamerinos", "conv-2", 6000, Duration.ofMinutes(4));

		assertThatThrownBy(() -> this.turnCostGuard.check("kamerinos", "conv-2"))
				.isInstanceOf(CostLimitExceededException.class)
				.satisfies(exception -> {
					CostLimitExceededException cost = (CostLimitExceededException) exception;
					assertThat(cost.scope()).isEqualTo(Scope.TENANT);
					assertThat(cost.measure()).isEqualTo(Measure.TOKENS);
					assertThat(cost.measured()).isEqualTo(12000);
				});
	}

	@Test
	@DisplayName("los turnos fuera de la ventana no cuentan")
	void ignoresTurnsOutsideTheWindow() {
		insertTurn("kamerinos", "conv-1", 6000, Duration.ofHours(2));
		insertTurn("kamerinos", "conv-1", 6000, Duration.ofHours(3));
		insertTurn("kamerinos", "conv-1", 6000, Duration.ofHours(4));

		assertThatCode(() -> this.turnCostGuard.check("kamerinos", "conv-1")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("el consumo de un tenant no afecta a otro")
	void isolatesTenants() {
		insertTurn("otro-tenant", "conv-1", 6000, Duration.ofMinutes(5));
		insertTurn("otro-tenant", "conv-1", 6000, Duration.ofMinutes(4));
		insertTurn("otro-tenant", "conv-1", 6000, Duration.ofMinutes(3));

		assertThatCode(() -> this.turnCostGuard.check("kamerinos", "conv-1")).doesNotThrowAnyException();
	}

	private void insertTurn(String tenantId, String conversationId, int tokens, Duration age) {
		this.jdbcTemplate.update(INSERT_TURN, UUID.randomUUID(), tenantId, conversationId, tokens,
				OffsetDateTime.ofInstant(Instant.now().minus(age), ZoneOffset.UTC));
	}
}
