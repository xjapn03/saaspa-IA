package com.juanp.saaspa.ia.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.BDDMockito.then;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.context.SecurityContextHolder;

import com.juanp.saaspa.ia.agent.customer.CustomerAgent;
import com.juanp.saaspa.ia.agent.customer.CustomerAgentConfig;
import com.juanp.saaspa.ia.agent.handoff.HandoffPolicy;
import com.juanp.saaspa.ia.api.dto.ChatRequestDto;
import com.juanp.saaspa.ia.api.dto.ChatResponseDto;
import com.juanp.saaspa.ia.backend.BackendClientConfig;
import com.juanp.saaspa.ia.config.LlmProperties;
import com.juanp.saaspa.ia.config.TenantProperties;
import com.juanp.saaspa.ia.security.TurnToken;
import com.juanp.saaspa.ia.security.TurnTokenAuthentication;
import com.juanp.saaspa.ia.tools.ToolsConfig;
import com.juanp.saaspa.ia.usage.ToolCallLogger;
import com.juanp.saaspa.ia.usage.TurnLogService;
import com.juanp.saaspa.ia.usage.TurnCostGuard;

import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regla R10 de verdad: ante un tema sensible, el {@code ChatController} no llama al modelo y devuelve
 * el texto canonico del codigo, de modo que un {@code ChatModel} doble que devolveria consejo de salud
 * jamas puede filtrar ese texto a la respuesta del endpoint.
 */
class ChatControllerHandoffTest {

	private static final UUID TURN_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

	private final AdviceChatModel adviceModel = new AdviceChatModel();

	private ApplicationContextRunner runner() {
		return new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
				.withUserConfiguration(BackendClientConfig.class, ToolsConfig.class, CustomerAgentConfig.class)
				.withBean(ChatModel.class, () -> this.adviceModel)
				.withBean(ChatClient.Builder.class, () -> ChatClient.builder(this.adviceModel))
				.withBean(ChatMemoryRepository.class, InMemoryChatMemoryRepository::new)
				.withBean(ToolCallLogger.class, () -> mock(ToolCallLogger.class))
				.withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
				.withPropertyValues("saaspa.backend.base-url=http://localhost:3001",
						"saaspa.backend.internal-api-key=test-key",
						"saaspa.tenant.display-name=Kamerinos SPA Bogota",
						"saaspa.tenant.timezone=America/Bogota",
						"saaspa.agent.memory-window=10");
	}

	@Test
	@DisplayName("un tema de salud no llega al modelo ni devuelve su consejo")
	void healthTopicNeverLeaksModelAdvice() {
		this.runner().run(context -> {
			CustomerAgent agent = context.getBean(CustomerAgent.class);
			HandoffPolicy policy = new HandoffPolicy();
			TurnLogService turnLogService = mock(TurnLogService.class);
			TurnCostGuard turnCostGuard = mock(TurnCostGuard.class);
			LlmProperties llmProperties = new LlmProperties(Duration.ofSeconds(3), Duration.ofSeconds(30),
					Duration.ofSeconds(35));
			TenantProperties tenantProperties = context.getBean(TenantProperties.class);
			ChatController controller = new ChatController(agent, turnLogService, policy, llmProperties,
					tenantProperties, turnCostGuard);

			TurnToken token = new TurnToken(TURN_ID.toString(), "kamerinos", "conv-1", TurnToken.Channel.WEB_WIDGET,
					TurnToken.Agent.CLIENTAS, null, null, Instant.now().plusSeconds(300), "turn-token-123");
			SecurityContextHolder.getContext().setAuthentication(new TurnTokenAuthentication(token));
			try {
				ChatResponseDto response = controller.chat(healthRequest());

				assertThat(response.reply().text())
						.isEqualTo(policy.canonicalReply(HandoffPolicy.Reason.HEALTH_TOPIC));
				assertThat(response.reply().text()).doesNotContain("masaje", "embarazo");
				assertThat(response.handoff().requested()).isTrue();
				assertThat(response.handoff().reason()).isEqualTo("HEALTH_TOPIC");
				assertThat(response.usage().model()).isNull();
				assertThat(this.adviceModel.calls()).isZero();
				// ADR 0010: un turno con handoff no llama al modelo, asi que no consulta el tope de coste
				// (y por tanto nunca se corta por presupuesto).
				then(turnCostGuard).shouldHaveNoInteractions();

				// ADR 0015 (punto 3 de ADR 0013): el turno derivado se registra con su propio estado y su
				// motivo, de modo que desde ia.turn_log no se confunde con un turno normal.
				ArgumentCaptor<TurnLogService.TurnLog> turnLog = ArgumentCaptor
						.forClass(TurnLogService.TurnLog.class);
				then(turnLogService).should().record(turnLog.capture());
				assertThat(turnLog.getValue().status()).isEqualTo(TurnLogService.Status.HANDOFF);
				assertThat(turnLog.getValue().handoffReason()).isEqualTo("HEALTH_TOPIC");
				assertThat(turnLog.getValue().errorCode()).isNull();
				assertThat(turnLog.getValue().model()).isNull();
				assertThat(turnLog.getValue().tokensIn()).isZero();
				assertThat(turnLog.getValue().tokensOut()).isZero();
			}
			finally {
				SecurityContextHolder.clearContext();
			}
		});
	}

	private static ChatRequestDto healthRequest() {
		return new ChatRequestDto(TURN_ID, "kamerinos", "conv-1", TurnToken.Channel.WEB_WIDGET,
				TurnToken.Agent.CLIENTAS,
				new ChatRequestDto.Identity(ChatRequestDto.IdentityKind.ANONYMOUS, null, null),
				new ChatRequestDto.Message("Estoy embarazada, puedo hacerme el masaje?"), "es-CO", "America/Bogota",
				OffsetDateTime.parse("2026-10-01T10:00:00-05:00"));
	}

	/**
	 * {@code ChatModel} doble que devolveria consejo de salud si se le llamara (nunca debe ocurrir ante
	 * un tema sensible). Cuenta las llamadas para poder afirmar que no se consulto.
	 */
	private static final class AdviceChatModel implements ChatModel {

		private final AtomicInteger calls = new AtomicInteger();

		int calls() {
			return this.calls.get();
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			this.calls.incrementAndGet();
			ChatResponseMetadata metadata = ChatResponseMetadata.builder()
					.model("test-model")
					.usage(new DefaultUsage(1, 1))
					.build();
			return new ChatResponse(
					List.of(new Generation(new AssistantMessage(
							"Durante el embarazo puedes hacerte el masaje relajante sin problema, no tiene contraindicaciones."))),
					metadata);
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.empty();
		}
	}
}