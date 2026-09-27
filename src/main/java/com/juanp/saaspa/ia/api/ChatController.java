package com.juanp.saaspa.ia.api;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.juanp.saaspa.ia.agent.customer.CustomerAgent;
import com.juanp.saaspa.ia.agent.handoff.HandoffPolicy;
import com.juanp.saaspa.ia.api.dto.ChatRequestDto;
import com.juanp.saaspa.ia.api.dto.ChatResponseDto;
import com.juanp.saaspa.ia.backend.BackendException;
import com.juanp.saaspa.ia.backend.BackendUnavailableException;
import com.juanp.saaspa.ia.config.LlmProperties;
import com.juanp.saaspa.ia.config.TenantProperties;
import com.juanp.saaspa.ia.security.CurrentTurnToken;
import com.juanp.saaspa.ia.security.TurnToken;
import com.juanp.saaspa.ia.usage.OriginHasher;
import com.juanp.saaspa.ia.usage.TurnCostGuard;
import com.juanp.saaspa.ia.usage.TurnLogService;

/**
 * Endpoint de chat que NestJS llama para cada turno ({@code POST /api/v1/chat}).
 *
 * <p>La parte de seguridad (clave de servicio y turn token) la resuelve la cadena de filtros; aqui solo
 * se comprueba que el contexto del cuerpo coincide con el token (R1), se enruta al agente, se registra
 * el turno en {@code ia.turn_log} (T1.6) y se traduce la respuesta al contrato. Las escrituras (crear o
 * mover citas) no existen en la Fase 1.
 */
@RestController
public class ChatController {

	/**
	 * Executor para aplicar el deadline del turno: hilos virtuales y propagacion del contexto de
	 * seguridad, de modo que las herramientas (que leen {@code CurrentTurnToken}) sigan funcionando
	 * dentro de la llamada al agente. Es un {@link ExecutorService} para poder cancelar la llamada en
	 * vuelo cuando expira el deadline (A-20).
	 */
	private static final ExecutorService TURN_EXECUTOR = new DelegatingSecurityContextExecutorService(
			Executors.newVirtualThreadPerTaskExecutor());

	private final CustomerAgent customerAgent;

	private final TurnLogService turnLogService;

	private final HandoffPolicy handoffPolicy;

	private final LlmProperties llmProperties;

	private final TenantProperties tenantProperties;

	private final TurnCostGuard turnCostGuard;

	private final OriginHasher originHasher;

	public ChatController(CustomerAgent customerAgent, TurnLogService turnLogService, HandoffPolicy handoffPolicy,
			LlmProperties llmProperties, TenantProperties tenantProperties, TurnCostGuard turnCostGuard,
			OriginHasher originHasher) {
		this.customerAgent = customerAgent;
		this.turnLogService = turnLogService;
		this.handoffPolicy = handoffPolicy;
		this.llmProperties = llmProperties;
		this.tenantProperties = tenantProperties;
		this.turnCostGuard = turnCostGuard;
		this.originHasher = originHasher;
	}

	/**
	 * Atiende un turno de la clienta.
	 *
	 * @param request turno enviado por NestJS
	 * @return respuesta del agente con enlaces, handoff y uso de tokens
	 */
	@PostMapping(path = "/api/v1/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ChatResponseDto chat(@Valid @RequestBody ChatRequestDto request) {
		TurnToken turnToken = CurrentTurnToken.require();
		TurnContextValidator.validate(turnToken, request);

		// A-03: solo se atienden turnos del tenant configurado (fallo cerrado, 403).
		if (!this.tenantProperties.defaultTenant().equals(turnToken.tenantId())) {
			throw new TenantNotAllowedException("El tenant del turn token no esta permitido");
		}

		if (turnToken.agent() != TurnToken.Agent.CLIENTAS) {
			throw new UnsupportedAgentException(
					"El agente " + turnToken.agent().name() + " estara disponible en la Fase 3");
		}

		long start = System.nanoTime();
		HandoffPolicy.Decision handoff = this.handoffPolicy.evaluate(request.message().text());
		// ADR 0020: el origen del turno (usuario o IP) se guarda hasheado y es la clave del tope por origen.
		// Se calcula una vez por turno: vale para las cuatro ramas que registran fila.
		String originHash = this.originHasher.hash(turnToken.origin());

		String replyText;
		String model;
		String promptVersion;
		Integer promptTokens;
		Integer completionTokens;
		TurnLogService.Status status;
		String handoffReason = null;
		if (handoff.requested()) {
			// R10: ante un tema sensible no se llama al modelo ni se devuelve su texto; la respuesta
			// es canonica y la escribe el codigo.
			replyText = this.handoffPolicy.canonicalReply(handoff.reason());
			model = null;
			promptVersion = null;
			promptTokens = 0;
			completionTokens = 0;
			// ADR 0015 (punto 3 de ADR 0013): el turno derivado deja fila con su propio estado y su
			// motivo, para que sea distinguible y auditable desde ia.turn_log. No llamo al modelo, asi
			// que no consume presupuesto y el guard de coste no lo cuenta.
			status = TurnLogService.Status.HANDOFF;
			handoffReason = handoff.reason().name();
		}
		else {
			// ADR 0010: el tope de coste se evalua solo en el camino que llama al modelo. Un turno
			// resuelto por HandoffPolicy no gasta tokens, asi que no se corta por presupuesto (R10 no
			// depende del coste). El turno rechazado por el tope NO se registra, para que un abuso no
			// escriba filas que alimenten su propio tope.
			this.turnCostGuard.check(turnToken.tenantId(), turnToken.conversationId(), originHash);
			try {
				CustomerAgent.CustomerReply reply = this.replyWithDeadline(turnToken, request.message().text());
				replyText = reply.text();
				model = reply.model();
				promptVersion = reply.promptVersion();
				promptTokens = reply.promptTokens();
				completionTokens = reply.completionTokens();
				status = TurnLogService.Status.OK;
			}
			catch (LlmTimeoutException ex) {
				// ADR 0014: un turno cortado por el deadline tambien deja fila, con estado (A-08).
				recordTurn(request, turnToken, TurnLogService.Status.DEADLINE, null, null, null, null, 0, 0,
						elapsedMs(start), originHash);
				throw ex;
			}
			catch (RuntimeException ex) {
				// ADR 0015 (H-05): el modelo ya se llamo, asi que el turno gasto presupuesto aunque no
				// haya respuesta. Sin esta fila, ia.turn_log no seria la fuente de verdad del consumo que
				// promete ADR 0010. Los tokens se desconocen y quedan en 0 (cota inferior).
				recordTurn(request, turnToken, TurnLogService.Status.ERROR, null, errorCode(ex).name(), null, null,
						0, 0, elapsedMs(start), originHash);
				throw ex;
			}
		}
		long latencyMs = elapsedMs(start);

		recordTurn(request, turnToken, status, handoffReason, null, promptVersion, model,
				promptTokens == null ? 0 : promptTokens, completionTokens == null ? 0 : completionTokens, latencyMs,
				originHash);

		return new ChatResponseDto(request.turnId(),
				new ChatResponseDto.Reply(replyText, List.of()),
				new ChatResponseDto.Handoff(handoff.requested(), handoff.reason() == null ? null : handoff.reason().name()),
				new ChatResponseDto.Usage(model, promptTokens, completionTokens),
				List.of());
	}

	/**
	 * Registra el turno en {@code ia.turn_log} con su estado (ADR 0014, ADR 0015 y ADR 0020). El registro
	 * nunca tumba el turno: si la escritura falla, {@link TurnLogService} lo anota y sigue.
	 */
	private void recordTurn(ChatRequestDto request, TurnToken turnToken, TurnLogService.Status status,
			String handoffReason, String errorCode, String promptVersion, String model, int tokensIn, int tokensOut,
			long latencyMs, String originHash) {
		this.turnLogService.record(new TurnLogService.TurnLog(request.turnId(), turnToken.tenantId(),
				turnToken.conversationId(), turnToken.channel().name(), turnToken.agent().name(), turnToken.userId(),
				turnToken.role() == null ? null : turnToken.role().name(), promptVersion, model, tokensIn, tokensOut,
				latencyMs, status, handoffReason, errorCode, originHash));
	}

	/**
	 * Traduce el fallo de un turno que ya llamo al modelo a un {@link TurnLogService.ErrorCode} (ADR 0015).
	 *
	 * <p>El mapeo es por origen, que es lo que este metodo puede saber con certeza: la unica via por la que
	 * una excepcion escapa del {@code try} es la llamada al agente, asi que lo que no sea un error del
	 * backend es un fallo del camino del modelo (proveedor, argumentos de herramienta o fallo inesperado
	 * dentro del agente). No se inspeccionan tipos del proveedor: el codigo tiene que seguir siendo valido
	 * si cambia la version de Spring AI.
	 */
	private static TurnLogService.ErrorCode errorCode(RuntimeException exception) {
		// BackendUnavailableException extiende BackendException: se comprueba primero la mas especifica.
		if (exception instanceof BackendUnavailableException) {
			return TurnLogService.ErrorCode.BACKEND_UNAVAILABLE;
		}
		if (exception instanceof BackendException) {
			return TurnLogService.ErrorCode.BACKEND_ERROR;
		}
		return TurnLogService.ErrorCode.MODEL_ERROR;
	}

	private static long elapsedMs(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	/**
	 * Llama al agente con un deadline por turno (ADR 0009): si se supera
	 * {@code saaspa.llm.turn-deadline}, se <strong>cancela</strong> la llamada en vuelo (interrupcion
	 * del hilo) y el turno falla con {@link LlmTimeoutException} (504), que lleva el {@code turnId} para
	 * correlacionar (ADR 0014).
	 */
	private CustomerAgent.CustomerReply replyWithDeadline(TurnToken turnToken, String message) {
		Future<CustomerAgent.CustomerReply> future = TURN_EXECUTOR
				.submit(() -> this.customerAgent.reply(turnToken, message));
		try {
			return future.get(this.llmProperties.turnDeadline().toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			future.cancel(true);
			throw new LlmTimeoutException("El modelo no respondio a tiempo", turnToken.turnId());
		}
		catch (InterruptedException ex) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new LlmTimeoutException("El turno fue interrumpido", turnToken.turnId());
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException("Fallo inesperado al llamar al agente", cause);
		}
	}
}
