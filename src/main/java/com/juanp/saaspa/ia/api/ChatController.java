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
import com.juanp.saaspa.ia.config.LlmProperties;
import com.juanp.saaspa.ia.config.TenantProperties;
import com.juanp.saaspa.ia.security.CurrentTurnToken;
import com.juanp.saaspa.ia.security.TurnToken;
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

	public ChatController(CustomerAgent customerAgent, TurnLogService turnLogService, HandoffPolicy handoffPolicy,
			LlmProperties llmProperties, TenantProperties tenantProperties, TurnCostGuard turnCostGuard) {
		this.customerAgent = customerAgent;
		this.turnLogService = turnLogService;
		this.handoffPolicy = handoffPolicy;
		this.llmProperties = llmProperties;
		this.tenantProperties = tenantProperties;
		this.turnCostGuard = turnCostGuard;
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

		String replyText;
		String model;
		String promptVersion;
		Integer promptTokens;
		Integer completionTokens;
		if (handoff.requested()) {
			// R10: ante un tema sensible no se llama al modelo ni se devuelve su texto; la respuesta
			// es canonica y la escribe el codigo.
			replyText = this.handoffPolicy.canonicalReply(handoff.reason());
			model = null;
			promptVersion = null;
			promptTokens = 0;
			completionTokens = 0;
		}
		else {
			// ADR 0010: el tope de coste se evalua solo en el camino que llama al modelo. Un turno
			// resuelto por HandoffPolicy no gasta tokens, asi que no se corta por presupuesto (R10 no
			// depende del coste).
			this.turnCostGuard.check(turnToken.tenantId(), turnToken.conversationId());
			try {
				CustomerAgent.CustomerReply reply = this.replyWithDeadline(turnToken, request.message().text());
				replyText = reply.text();
				model = reply.model();
				promptVersion = reply.promptVersion();
				promptTokens = reply.promptTokens();
				completionTokens = reply.completionTokens();
			}
			catch (LlmTimeoutException ex) {
				// ADR 0014: un turno cortado por el deadline tambien deja fila, con estado (A-08).
				recordTurn(request, turnToken, TurnLogService.Status.DEADLINE, null, null, 0, 0, elapsedMs(start));
				throw ex;
			}
		}
		long latencyMs = elapsedMs(start);

		recordTurn(request, turnToken, TurnLogService.Status.OK, promptVersion, model,
				promptTokens == null ? 0 : promptTokens, completionTokens == null ? 0 : completionTokens, latencyMs);

		return new ChatResponseDto(request.turnId(),
				new ChatResponseDto.Reply(replyText, List.of()),
				new ChatResponseDto.Handoff(handoff.requested(), handoff.reason() == null ? null : handoff.reason().name()),
				new ChatResponseDto.Usage(model, promptTokens, completionTokens),
				List.of());
	}

	/**
	 * Registra el turno en {@code ia.turn_log} con su estado (ADR 0014). El registro nunca tumba el turno:
	 * si la escritura falla, {@link TurnLogService} lo anota y sigue.
	 */
	private void recordTurn(ChatRequestDto request, TurnToken turnToken, TurnLogService.Status status,
			String promptVersion, String model, int tokensIn, int tokensOut, long latencyMs) {
		this.turnLogService.record(new TurnLogService.TurnLog(request.turnId(), turnToken.tenantId(),
				turnToken.conversationId(), turnToken.channel().name(), turnToken.agent().name(), turnToken.userId(),
				turnToken.role() == null ? null : turnToken.role().name(), promptVersion, model, tokensIn, tokensOut,
				latencyMs, status));
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
