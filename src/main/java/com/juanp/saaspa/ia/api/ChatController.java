package com.juanp.saaspa.ia.api;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.juanp.saaspa.ia.agent.customer.CustomerAgent;
import com.juanp.saaspa.ia.agent.handoff.HandoffPolicy;
import com.juanp.saaspa.ia.api.dto.ChatRequestDto;
import com.juanp.saaspa.ia.api.dto.ChatResponseDto;
import com.juanp.saaspa.ia.security.CurrentTurnToken;
import com.juanp.saaspa.ia.security.TurnToken;
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

	private final CustomerAgent customerAgent;

	private final TurnLogService turnLogService;

	private final HandoffPolicy handoffPolicy;

	public ChatController(CustomerAgent customerAgent, TurnLogService turnLogService, HandoffPolicy handoffPolicy) {
		this.customerAgent = customerAgent;
		this.turnLogService = turnLogService;
		this.handoffPolicy = handoffPolicy;
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
			CustomerAgent.CustomerReply reply = this.customerAgent.reply(turnToken, request.message().text());
			replyText = reply.text();
			model = reply.model();
			promptVersion = reply.promptVersion();
			promptTokens = reply.promptTokens();
			completionTokens = reply.completionTokens();
		}
		long latencyMs = (System.nanoTime() - start) / 1_000_000;

		this.turnLogService.record(new TurnLogService.TurnLog(request.turnId(), turnToken.tenantId(),
				turnToken.conversationId(), turnToken.channel().name(), turnToken.agent().name(), turnToken.userId(),
				turnToken.role() == null ? null : turnToken.role().name(), promptVersion, model,
				promptTokens == null ? 0 : promptTokens,
				completionTokens == null ? 0 : completionTokens, latencyMs));

		return new ChatResponseDto(request.turnId(),
				new ChatResponseDto.Reply(replyText, List.of()),
				new ChatResponseDto.Handoff(handoff.requested(), handoff.reason() == null ? null : handoff.reason().name()),
				new ChatResponseDto.Usage(model, promptTokens, completionTokens),
				List.of());
	}
}
