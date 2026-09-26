package com.juanp.saaspa.ia;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.juanp.saaspa.ia.api.ChatController;
import com.juanp.saaspa.ia.api.dto.ChatRequestDto;
import com.juanp.saaspa.ia.api.dto.ChatResponseDto;
import com.juanp.saaspa.ia.security.TurnToken;
import com.juanp.saaspa.ia.security.TurnTokenAuthentication;
import com.juanp.saaspa.ia.tools.CustomerTools;
import com.juanp.saaspa.ia.usage.LoggingToolCallback;
import com.juanp.saaspa.ia.usage.ToolCallLogger;

import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

/**
 * Turno integro con Testcontainers Postgres (T1.9): un {@code ChatModel} guionizado pide una
 * herramienta, la herramienta se ejecuta de verdad contra el backend (WireMock) y el turno y la tool
 * call quedan registrados en el esquema {@code ia}. Ningun test llama a un LLM real (R14).
 */
@SpringBootTest(properties = { "spring.ai.deepseek.api-key=test-key" })
@Import({ TestcontainersConfiguration.class, TurnToolIntegrationTest.ScriptedModelConfig.class })
class TurnToolIntegrationTest {

	private static final String SERVICES_PATH = "/api/internal/v1/services";

	private static final UUID TURN_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

	private static final String CATALOGUE_JSON = """
			{"data":[{"id":"srv-1","name":"Masaje relajante","slug":"masaje-relajante",
			  "description":"Masaje de 60 minutos","price":120000.0,"compareAtPrice":null,"duration":60,
			  "isActive":true,"isFeatured":true,
			  "category":{"id":"cat-1","name":"Masajes","slug":"masajes"}}],
			 "total":1,"page":1,"limit":50,"totalPages":1}
			""";

	private static final WireMockServer BACKEND = new WireMockServer(WireMockConfiguration.options().dynamicPort());

	static {
		BACKEND.start();
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("saaspa.backend.base-url", BACKEND::baseUrl);
		registry.add("saaspa.backend.internal-api-key", () -> "test-key");
		registry.add("saaspa.tenant.default", () -> "kamerinos");
	}

	@Autowired
	private ChatController chatController;

	@Autowired
	private CustomerTools customerTools;

	@Autowired
	private ToolCallLogger toolCallLogger;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		BACKEND.resetAll();
		this.jdbcTemplate.update("DELETE FROM ia.turn_log");
		this.jdbcTemplate.update("DELETE FROM ia.tool_call_log");
		SecurityContextHolder.getContext().setAuthentication(new TurnTokenAuthentication(
				new TurnToken(TURN_ID.toString(), "kamerinos", "conv-it", TurnToken.Channel.WEB_WIDGET,
						TurnToken.Agent.CLIENTAS, null, null, Instant.now().plusSeconds(300), "turn-token-123")));
	}

	@AfterAll
	static void stopBackend() {
		SecurityContextHolder.clearContext();
		BACKEND.stop();
	}

	@Test
	@DisplayName("un turno integro se persiste y devuelve la respuesta del modelo")
	void runsTurnAndPersistsIt() {
		ChatResponseDto response = this.chatController.chat(request());

		assertThat(response.reply().text()).isEqualTo(ScriptedChatModel.REPLY_TEXT);
		assertThat(response.usage().model()).isEqualTo("scripted-model");
		assertThat(count("ia.turn_log")).isEqualTo(1);
		assertThat(this.jdbcTemplate.queryForObject("SELECT tenant_id FROM ia.turn_log", String.class))
				.isEqualTo("kamerinos");
	}

	@Test
	@DisplayName("la herramienta se ejecuta de verdad contra el backend y se registra en la tool call log")
	void executesToolAndLogsIt() {
		BACKEND.stubFor(get(urlPathEqualTo(SERVICES_PATH)).willReturn(okJson(CATALOGUE_JSON)));

		ToolCallback tool = Arrays.stream(ToolCallbacks.from(this.customerTools))
				.filter(callback -> callback.getToolDefinition().name().equals("listarServicios"))
				.findFirst()
				.orElseThrow();
		new LoggingToolCallback(tool, this.toolCallLogger, this.objectMapper).call("{}");

		BACKEND.verify(getRequestedFor(urlPathEqualTo(SERVICES_PATH))
				.withHeader("Authorization", equalTo("Bearer turn-token-123")));
		assertThat(count("ia.tool_call_log")).isEqualTo(1);
		assertThat(this.jdbcTemplate.queryForObject("SELECT tool_name FROM ia.tool_call_log", String.class))
				.isEqualTo("listarServicios");
	}

	private static ChatRequestDto request() {
		return new ChatRequestDto(TURN_ID, "kamerinos", "conv-it", TurnToken.Channel.WEB_WIDGET,
				TurnToken.Agent.CLIENTAS,
				new ChatRequestDto.Identity(ChatRequestDto.IdentityKind.ANONYMOUS, null, null, null),
				new ChatRequestDto.Message("¿Cuánto cuesta el masaje relajante?"), "es-CO", "America/Bogota",
				OffsetDateTime.parse("2026-10-01T10:00:00-05:00"));
	}

	private int count(String table) {
		return this.jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ScriptedModelConfig {

		@Bean
		@Primary
		ChatModel scriptedChatModel() {
			return new ScriptedChatModel();
		}
	}

	/** Modelo guionizado: devuelve una respuesta fija (no llama a un LLM real, R14). */
	static final class ScriptedChatModel implements ChatModel {

		static final String REPLY_TEXT = "El masaje relajante cuesta $ 120.000.";

		@Override
		public ChatResponse call(Prompt prompt) {
			ChatResponseMetadata metadata = ChatResponseMetadata.builder()
					.model("scripted-model")
					.usage(new DefaultUsage(10, 5))
					.build();
			return new ChatResponse(List.of(new Generation(new AssistantMessage(REPLY_TEXT))), metadata);
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.empty();
		}
	}
}