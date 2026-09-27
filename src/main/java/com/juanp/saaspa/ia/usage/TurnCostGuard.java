package com.juanp.saaspa.ia.usage;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p><strong>Tope por origen (ADR 0020, hallazgo H-04):</strong> el tope del tenant lo podia agotar una sola
 * IP en ~12 minutos (su {@code Throttler} permite 20 req/min), dejando fuera a todas las clientas. Se anade
 * un cuarto tope, por <em>origen del turno</em> (el usuario si esta identificado, la IP resuelta por el
 * backend si es anonimo), que es lo unico que no se puede rotar. Se evalua **el ultimo** para que el
 * {@code scope} del 429 sea el mas preciso: una conversacion sola que habla de mas se etiqueta como
 * {@code conversation}, una rotacion de conversaciones desde un mismo origen como {@code origin}, y un
 * consumo alto repartido entre varios origenes como {@code tenant} (que es la senal de capacidad).
 */
public class TurnCostGuard {

	private static final Logger log = LoggerFactory.getLogger(TurnCostGuard.class);

	/**
	 * Consumo del tenant, de la conversacion y del origen en la ventana, en una sola consulta. Los
	 * parametros son el id de conversacion (dos veces, para el filtro por fila), el hash del origen, el
	 * tenant y el inicio de la ventana.
	 *
	 * <p>El filtro {@code status <> 'HANDOFF'} es la base del computo (ADR 0015): se cuenta todo desenlace
	 * que <em>llamo al modelo</em>, y un turno derivado no lo hace. El nombre del estado se toma del enum
	 * para que un renombrado no pueda dejar el filtro mintiendo en silencio; la clasificacion completa la
	 * fija {@code TurnOutcomeClassificationTest}, con un {@code switch} exhaustivo.
	 *
	 * <p>Un turno sin origen ({@code origin_hash} nulo) no entra en ninguna cubeta de origen: comparar con
	 * {@code NULL} no cuenta filas. Sigue contando para los topes de tenant y de conversacion.
	 */
	private static final String USAGE_IN_WINDOW = """
			SELECT count(*) AS tenant_turns,
			       COALESCE(sum(tokens_in + tokens_out), 0) AS tenant_tokens,
			       count(*) FILTER (WHERE conversation_id = ?) AS conversation_turns,
			       COALESCE(sum(tokens_in + tokens_out) FILTER (WHERE conversation_id = ?), 0) AS conversation_tokens,
			       count(*) FILTER (WHERE origin_hash = ?) AS origin_turns
			FROM ia.turn_log
			WHERE tenant_id = ? AND created_at >= ? AND status <> '%s'
			""".formatted(TurnLogService.Status.HANDOFF.name());

	private final JdbcTemplate jdbcTemplate;

	private final CostGuardProperties properties;

	private final Clock clock;

	private final AtomicBoolean warnedAboutMissingOrigin = new AtomicBoolean();

	public TurnCostGuard(JdbcTemplate jdbcTemplate, CostGuardProperties properties) {
		this(jdbcTemplate, properties, Clock.systemDefaultZone());
	}

	TurnCostGuard(JdbcTemplate jdbcTemplate, CostGuardProperties properties, Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Comprueba los topes del tenant, de la conversacion y del origen.
	 *
	 * @param tenantId tenant del turno
	 * @param conversationId conversacion del turno
	 * @param originHash hash del origen del turno ({@code null} si el turn token todavia no trae el claim
	 * {@code clientIp} ni usuario; entonces el tope por origen no se aplica)
	 * @throws CostLimitExceededException si el turno supera alguno de los topes
	 */
	public void check(String tenantId, String conversationId, String originHash) {
		if (!this.properties.enabled()) {
			return;
		}
		if (originHash == null) {
			warnAboutMissingOrigin();
		}
		OffsetDateTime since = OffsetDateTime.ofInstant(this.clock.instant().minus(this.properties.window()),
				ZoneOffset.UTC);
		Map<String, Object> usage = this.jdbcTemplate.queryForMap(USAGE_IN_WINDOW, conversationId, conversationId,
				originHash, tenantId, since);

		long tenantTurns = number(usage.get("tenant_turns"));
		long tenantTokens = number(usage.get("tenant_tokens"));
		long conversationTurns = number(usage.get("conversation_turns"));
		long conversationTokens = number(usage.get("conversation_tokens"));
		long originTurns = number(usage.get("origin_turns"));

		// El turno que se esta atendiendo contaria una fila mas y sus tokens, asi que el tope salta al
		// alcanzarlo (>=) y no al superarlo.
		require(tenantTurns, this.properties.tenantMaxTurns(), Scope.TENANT, Measure.TURNS);
		require(tenantTokens, this.properties.tenantMaxTokens(), Scope.TENANT, Measure.TOKENS);
		require(conversationTurns, this.properties.conversationMaxTurns(), Scope.CONVERSATION, Measure.TURNS);
		require(conversationTokens, this.properties.conversationMaxTokens(), Scope.CONVERSATION, Measure.TOKENS);
		// Ultimo a proposito (ver el javadoc de la clase): el `scope` del 429 queda lo mas preciso posible.
		if (originHash != null) {
			require(originTurns, this.properties.originMaxTurns(), Scope.ORIGIN, Measure.TURNS);
		}
	}

	/**
	 * Un turno sin origen significa que el turn token no trae {@code clientIp} ni usuario. El backend ya emite
	 * ese claim (su PR #84, ADR 0020), asi que verlo aqui delata un despliegue que no lo esta mandando o un
	 * token viejo: en ese caso el tope por origen (el que acota el abuso de una sola IP) no se aplica. Se avisa
	 * <strong>una vez por instancia</strong> para no inundar el log en cada turno; los turnos sin origen quedan
	 * ademas visibles en {@code ia.turn_log} ({@code origin_hash IS NULL}).
	 */
	private void warnAboutMissingOrigin() {
		if (this.warnedAboutMissingOrigin.compareAndSet(false, true)) {
			log.warn("Turno sin origen (el turn token no trae clientIp y no hay usuario): el tope por origen "
					+ "de ADR 0020 no se aplica. El backend ya emite ese claim: revisar su despliegue");
		}
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
