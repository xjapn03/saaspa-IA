package com.juanp.saaspa.ia.api;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.juanp.saaspa.ia.agent.customer.CustomerAgent;
import com.juanp.saaspa.ia.api.dto.ChatRequestDto;
import com.juanp.saaspa.ia.api.dto.ChatResponseDto;
import com.juanp.saaspa.ia.security.CurrentTurnToken;
import com.juanp.saaspa.ia.security.TurnToken;

/**
 * Endpoint de chat que NestJS llama para cada turno ({@code POST /api/v1/chat}).
 *
 * <p>La parte de seguridad (clave de servicio y turn token) la resuelve la cadena de filtros; aqui solo
 * se comprueba que el contexto del cuerpo coincide con el token (R1), se enruta al agente y se traduce
 * su respuesta al contrato. Las escrituras (crear o mover citas) no existen en la Fase 1.
 */
@RestController
public class ChatController {

	private final CustomerAgent customerAgent;

	public ChatController(CustomerAgent customerAgent) {
		this.customerAgent = customerAgent;
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

		CustomerAgent.CustomerReply reply = this.customerAgent.reply(turnToken, request.message().text());

		return new ChatResponseDto(request.turnId(),
				new ChatResponseDto.Reply(reply.text(), List.of()),
				new ChatResponseDto.Handoff(false, null),
				new ChatResponseDto.Usage(reply.model(), reply.promptTokens(), reply.completionTokens()),
				List.of());
	}
}
