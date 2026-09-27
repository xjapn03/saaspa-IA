package com.juanp.saaspa.ia.usage;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

import com.juanp.saaspa.ia.config.CostGuardProperties;
import com.juanp.saaspa.ia.usage.CostLimitExceededException.Measure;
import com.juanp.saaspa.ia.usage.CostLimitExceededException.Scope;

/**
 * Tope de coste por tenant y por conversacion (ADR 0010, la mitad que corresponde a este servicio).
 *
 * <p>La fuente de verdad es {@code ia.turn_log}: la ventana se calcula con una consulta agregada sobre los
 * turnos ya registrados (tokens de entrada + salida), sin contador en memoria, de modo que un reinicio del
 * servicio no borra el consumo acumulado y varias instancias comparten el mismo limite.
 *
 * <p><strong>Base del computo (ADR 0015):</strong> se cuenta todo desenlace que haya llamado al modelo. Un
 * turno derivado por handoff no lo hace y no entra; uno que fallo despues de llamarlo ({@code ERROR}) si
 * entra, porque gasto presupuesto. Sus tokens se desconocen y se registran en 0, asi que las medidas de
 * tokens son una <em>cota inferior</em> y las de turnos son exactas.
 *
 * <p>Se evalua **antes** de llamar al modelo y solo en la rama que llama al modelo: un turno resuelto por
 * {@code HandoffPolicy} no gasta tokens y por tanto no debe cortarse por presupuesto (R10 no depende del
 * coste).
 */
public class TurnCostGuard {

	/**
	 * Consumo del tenant y de la conversacion en la ventana, en una sola consulta. Los dos primeros
	 * parametros son el id de conversacion (dos veces, para el filtro por fila) y despues el tenant y el
	 * inicio de la ventana.
	 *
	 * <p>El filtro {@code status <> 'HANDOFF'} es la base del computo (ADR 0015): se cuenta todo desenlace
	 * que <em>llamo al modelo</em>, y un turno derivado no lo hace. El nombre del estado se toma del enum
	 * para que un renombrado no pueda dejar el filtro mintiendo en silencio; la clasificacion completa la
	 * fija {@code TurnOutcomeClassificationTest}, con un {@code switch} exhaustivo.
	 */
	private static final String USAGE_IN_WINDOW = """
			SELECT count(*) AS tenant_turns,
			       COALESCE(sum(tokens_in + tokens_out), 0) AS tenant_tokens,
			       count(*) FILTER (WHERE conversation_id = ?) AS conversation_turns,
			       COALESCE(sum(tokens_in + tokens_out) FILTER (WHERE conversation_id = ?), 0) AS conversation_tokens
			FROM ia.turn_log
			WHERE tenant_id = ? AND created_at >= ? AND status <> '%s'
			""".formatted(TurnLogService.Status.HANDOFF.name());

	private final JdbcTemplate jdbcTemplate;

	private final CostGuardProperties properties;

	private final Clock clock;

	public TurnCostGuard(JdbcTemplate jdbcTemplate, CostGuardProperties properties) {
		this(jdbcTemplate, properties, Clock.systemDefaultZone());
	}

	TurnCostGuard(JdbcTemplate jdbcTemplate, CostGuardProperties properties, Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Comprueba los topes del tenant y de la conversacion.
	 *
	 * @param tenantId tenant del turno
	 * @param conversationId conversacion del turno
	 * @throws CostLimitExceededException si el turno supera alguno de los topes
	 */
	public void check(String tenantId, String conversationId) {
		if (!this.properties.enabled()) {
			return;
		}
		OffsetDateTime since = OffsetDateTime.ofInstant(this.clock.instant().minus(this.properties.window()),
				ZoneOffset.UTC);
		Map<String, Object> usage = this.jdbcTemplate.queryForMap(USAGE_IN_WINDOW, conversationId, conversationId,
				tenantId, since);

		long tenantTurns = number(usage.get("tenant_turns"));
		long tenantTokens = number(usage.get("tenant_tokens"));
		long conversationTurns = number(usage.get("conversation_turns"));
		long conversationTokens = number(usage.get("conversation_tokens"));

		// El turno que se esta atendiendo contaria una fila mas y sus tokens, asi que el tope salta al
		// alcanzarlo (>=) y no al superarlo.
		require(tenantTurns, this.properties.tenantMaxTurns(), Scope.TENANT, Measure.TURNS);
		require(tenantTokens, this.properties.tenantMaxTokens(), Scope.TENANT, Measure.TOKENS);
		require(conversationTurns, this.properties.conversationMaxTurns(), Scope.CONVERSATION, Measure.TURNS);
		require(conversationTokens, this.properties.conversationMaxTokens(), Scope.CONVERSATION, Measure.TOKENS);
	}

	private void require(long measured, long limit, Scope scope, Measure measure) {
		if (measured >= limit) {
			throw new CostLimitExceededException(scope, measure, limit, measured, this.properties.window());
		}
	}

	private static long number(Object value) {
		return value instanceof Number number ? number.longValue() : 0L;
	}
}
