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
 * Tope de coste por tenant, por conversacion y por origen del turno (ADR 0010 y ADR 0020) contra PostgreSQL
 * real: el consumo se mide con una consulta agregada sobre {@code ia.turn_log}, asi que lo que hay que
 * verificar de verdad es la ventana y los cuatro topes, no un contador simulado.
 *
 * <p>Los topes del test son pequenos a proposito para poder superarlos con pocas filas.
 */
@SpringBootTest(properties = { "spring.ai.deepseek.api-key=test-key", "saaspa.cost-guard.window=1h",
		"saaspa.cost-guard.tenant-max-turns=5", "saaspa.cost-guard.tenant-max-tokens=10000",
		"saaspa.cost-guard.conversation-max-turns=3", "saaspa.cost-guard.conversation-max-tokens=5000",
		"saaspa.cost-guard.origin-max-turns=4" })
@Import(TestcontainersConfiguration.class)
class TurnCostGuardTest {

	private static final String INSERT_TURN = """
			INSERT INTO ia.turn_log (turn_id, tenant_id, conversation_id, channel, agent, prompt_version, model,
				tokens_in, tokens_out, latency_ms, status, origin_hash, created_at)
			VALUES (?, ?, ?, 'WEB_WIDGET', 'CLIENTAS', 'customer-agent.v2', 'deepseek-flash', ?, 0, 100, ?, ?, ?)
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

		assertThatCode(() -> this.turnCostGuard.check("kamerinos", "conv-1", null)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("corta por el tope de turnos de la conversacion")
	void rejectsTurnsAtTheConversationLimit() {
		insertTurn("kamerinos", "conv-1", 10, Duration.ofMinutes(5));
		insertTurn("kamerinos", "conv-1", 10, Duration.ofMinutes(4));
		insertTurn("kamerinos", "conv-1", 10, Duration.ofMinutes(3));

		assertThatThrownBy(() -> this.turnCostGuard.check("kamerinos", "conv-1", null))
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

		assertThatThrownBy(() -> this.turnCostGuard.check("kamerinos", "conv-1", null))
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

		assertThatThrownBy(() -> this.turnCostGuard.check("kamerinos", "conv-nueva", null))
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

		assertThatThrownBy(() -> this.turnCostGuard.check("kamerinos", "conv-2", null))
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

		assertThatCode(() -> this.turnCostGuard.check("kamerinos", "conv-1", null)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("el consumo de un tenant no afecta a otro")
	void isolatesTenants() {
		insertTurn("otro-tenant", "conv-1", 6000, Duration.ofMinutes(5));
		insertTurn("otro-tenant", "conv-1", 6000, Duration.ofMinutes(4));
		insertTurn("otro-tenant", "conv-1", 6000, Duration.ofMinutes(3));

		assertThatCode(() -> this.turnCostGuard.check("kamerinos", "conv-1", null)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("los turnos derivados por handoff no cuentan para los topes de turnos (ADR 0015)")
	void handoffTurnsDoNotCount() {
		// Cinco filas HANDOFF: alcanzarian el tope del tenant (5) y tres de ellas el de la conversacion (3)
		// si el guard contara por count(*) a secas, que es lo que H-05 vino a corregir.
		insertTurn("kamerinos", "conv-1", 0, TurnLogService.Status.HANDOFF, Duration.ofMinutes(5));
		insertTurn("kamerinos", "conv-1", 0, TurnLogService.Status.HANDOFF, Duration.ofMinutes(4));
		insertTurn("kamerinos", "conv-1", 0, TurnLogService.Status.HANDOFF, Duration.ofMinutes(3));
		insertTurn("kamerinos", "conv-2", 0, TurnLogService.Status.HANDOFF, Duration.ofMinutes(2));
		insertTurn("kamerinos", "conv-2", 0, TurnLogService.Status.HANDOFF, Duration.ofMinutes(1));

		assertThatCode(() -> this.turnCostGuard.check("kamerinos", "conv-1", null)).doesNotThrowAnyException();
		assertThatCode(() -> this.turnCostGuard.check("kamerinos", "conv-nueva", null)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("un turno fallido despues del modelo si cuenta: gasto presupuesto (ADR 0015)")
	void errorTurnsDoCount() {
		// Los tokens de un turno ERROR se desconocen (0), pero el turno se llamo al modelo y consumio
		// presupuesto: el tope de turnos lo tiene que ver. Aqui las 3 filas agotan el tope de la conversacion.
		insertTurn("kamerinos", "conv-1", 0, TurnLogService.Status.ERROR, Duration.ofMinutes(5));
		insertTurn("kamerinos", "conv-1", 0, TurnLogService.Status.ERROR, Duration.ofMinutes(4));
		insertTurn("kamerinos", "conv-1", 0, TurnLogService.Status.ERROR, Duration.ofMinutes(3));

		assertThatThrownBy(() -> this.turnCostGuard.check("kamerinos", "conv-1", null))
				.isInstanceOf(CostLimitExceededException.class)
				.satisfies(exception -> {
					CostLimitExceededException cost = (CostLimitExceededException) exception;
					assertThat(cost.scope()).isEqualTo(Scope.CONVERSATION);
					assertThat(cost.measure()).isEqualTo(Measure.TURNS);
					assertThat(cost.measured()).isEqualTo(3);
				});
	}

	@Test
	@DisplayName("el tope del tenant tampoco se infla con turnos derivados (ADR 0015)")
	void handoffTurnsDoNotCountForTheTenantLimit() {
		for (int i = 0; i < 5; i++) {
			insertTurn("kamerinos", "conv-" + i, 0, TurnLogService.Status.HANDOFF, Duration.ofMinutes(5));
		}
		// Una fila real (OK) deja el tenant en 1: muy por debajo de sus 5 turnos.
		insertTurn("kamerinos", "conv-real", 100, Duration.ofMinutes(4));

		assertThatCode(() -> this.turnCostGuard.check("kamerinos", "conv-nueva", null)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("corta por el tope del origen aunque el atacante rote conversaciones (ADR 0020 / H-04)")
	void rejectsTurnsAtTheOriginLimitAcrossConversations() {
		// El caso exacto de H-04: una sola IP que rota conversationId para agotar el presupuesto del tenant.
		// Cada conversacion queda por debajo de su tope (3) y el tenant por debajo del suyo (5), asi que lo
		// unico que lo corta es el tope por origen (4): antes de ADR 0020 se llevaba los 240 del tenant.
		insertTurn("kamerinos", "conv-1", 10, TurnLogService.Status.OK, "hash-atacante", Duration.ofMinutes(5));
		insertTurn("kamerinos", "conv-2", 10, TurnLogService.Status.OK, "hash-atacante", Duration.ofMinutes(4));
		insertTurn("kamerinos", "conv-3", 10, TurnLogService.Status.OK, "hash-atacante", Duration.ofMinutes(3));
		insertTurn("kamerinos", "conv-4", 10, TurnLogService.Status.OK, "hash-atacante", Duration.ofMinutes(2));

		assertThatThrownBy(() -> this.turnCostGuard.check("kamerinos", "conv-nueva", "hash-atacante"))
				.isInstanceOf(CostLimitExceededException.class)
				.satisfies(exception -> {
					CostLimitExceededException cost = (CostLimitExceededException) exception;
					assertThat(cost.scope()).isEqualTo(Scope.ORIGIN);
					assertThat(cost.measure()).isEqualTo(Measure.TURNS);
					assertThat(cost.limit()).isEqualTo(4);
					assertThat(cost.measured()).isEqualTo(4);
				});
	}

	@Test
	@DisplayName("el consumo de un origen no cuenta para otro (ADR 0020)")
	void isolatesOrigins() {
		insertTurn("kamerinos", "conv-1", 10, TurnLogService.Status.OK, "hash-a", Duration.ofMinutes(5));
		insertTurn("kamerinos", "conv-1", 10, TurnLogService.Status.OK, "hash-a", Duration.ofMinutes(4));
		insertTurn("kamerinos", "conv-1", 10, TurnLogService.Status.OK, "hash-a", Duration.ofMinutes(3));

		// Otro origen en una conversacion nueva: nada suyo ha consumido y el tenant va por 3 de 5.
		assertThatCode(() -> this.turnCostGuard.check("kamerinos", "conv-nueva", "hash-b"))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("un turno sin origen se tolera: no entra en cubetas de origen y sigue contando al tenant (ADR 0020)")
	void turnsWithoutOriginAreTolerated() {
		// Cuatro filas sin origen (el backend aun no emite el claim clientIp): `origin_hash = NULL` no es
		// cierto para ninguna fila, asi que no llenan la cubeta de origen de nadie.
		insertTurn("kamerinos", "conv-1", 10, TurnLogService.Status.OK, null, Duration.ofMinutes(5));
		insertTurn("kamerinos", "conv-2", 10, TurnLogService.Status.OK, null, Duration.ofMinutes(4));
		insertTurn("kamerinos", "conv-3", 10, TurnLogService.Status.OK, null, Duration.ofMinutes(3));
		insertTurn("kamerinos", "conv-4", 10, TurnLogService.Status.OK, null, Duration.ofMinutes(2));
		insertTurn("kamerinos", "conv-5", 10, TurnLogService.Status.OK, null, Duration.ofMinutes(1));

		// Sin claim, el tope por origen no se aplica (y no revienta): el que salta es el del tenant.
		assertThatThrownBy(() -> this.turnCostGuard.check("kamerinos", "conv-nueva", null))
				.isInstanceOf(CostLimitExceededException.class)
				.satisfies(exception -> assertThat(((CostLimitExceededException) exception).scope())
						.isEqualTo(Scope.TENANT));
	}

	private void insertTurn(String tenantId, String conversationId, int tokens, Duration age) {
		insertTurn(tenantId, conversationId, tokens, TurnLogService.Status.OK, null, age);
	}

	private void insertTurn(String tenantId, String conversationId, int tokens, TurnLogService.Status status,
			Duration age) {
		insertTurn(tenantId, conversationId, tokens, status, null, age);
	}

	private void insertTurn(String tenantId, String conversationId, int tokens, TurnLogService.Status status,
			String originHash, Duration age) {
		this.jdbcTemplate.update(INSERT_TURN, UUID.randomUUID(), tenantId, conversationId, tokens, status.name(),
				originHash, OffsetDateTime.ofInstant(Instant.now().minus(age), ZoneOffset.UTC));
	}
}
