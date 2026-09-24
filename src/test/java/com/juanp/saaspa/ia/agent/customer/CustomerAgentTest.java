package com.juanp.saaspa.ia.agent.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.juanp.saaspa.ia.backend.BackendClientConfig;
import com.juanp.saaspa.ia.config.TenantProperties;
import com.juanp.saaspa.ia.security.TurnToken;
import com.juanp.saaspa.ia.tools.CustomerTools;
import com.juanp.saaspa.ia.tools.ToolsConfig;

/**
 * Comportamiento del agente CLIENTAS (T1.5) con un {@code ChatModel} doble: prompt, herramientas,
 * memoria con ventana y nombre de conversacion. Ningun test llama a un LLM real (R14).
 */
class CustomerAgentTest {

	/** Reloj fijo: en Bogota es el jueves 1 de octubre de 2026. */
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"),
			ZoneId.of("America/Bogota"));

	private final TestChatModel chatModel = new TestChatModel();

	private ApplicationContextRunner runner(int memoryWindow) {
		return new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
				.withUserConfiguration(BackendClientConfig.class, ToolsConfig.class, CustomerAgentConfig.class)
				.withBean(ChatModel.class, () -> this.chatModel)
				.withBean(ChatClient.Builder.class, () -> ChatClient.builder(this.chatModel))
				.withBean(ChatMemoryRepository.class, InMemoryChatMemoryRepository::new)
				.withPropertyValues("saaspa.backend.base-url=http://localhost:3001",
						"saaspa.backend.internal-api-key=test-key", "saaspa.tenant.display-name=Kamerinos SPA Bogota",
						"saaspa.tenant.timezone=America/Bogota", "saaspa.agent.memory-window=" + memoryWindow);
	}

	@Test
	@DisplayName("el prompt inyecta el negocio, la fecha de hoy y la zona horaria")
	void promptCarriesTenantDateAndTimeZone() {
		runner(10).run(context -> {
			agent(context).reply(turnToken(TurnToken.Channel.WEB_WIDGET, "conv-1"), "Cuanto cuesta un masaje?");

			Prompt prompt = this.chatModel.lastPrompt();
			assertThat(systemText(prompt)).contains("Kamerinos SPA Bogota")
					.contains("centro de estética y bienestar")
					.contains("2026-10-01")
					.contains("jueves 1 de octubre de 2026")
					.contains("America/Bogota");
			assertThat(userTexts(prompt)).containsExactly("Cuanto cuesta un masaje?");
		});
	}

	@Test
	@DisplayName("devuelve el texto y el uso de tokens del modelo con la version del prompt")
	void replyCarriesTextUsageAndPromptVersion() {
		runner(10).run(context -> {
			this.chatModel.replyWith("El masaje relajante cuesta $ 120.000.");

			CustomerAgent.CustomerReply reply = agent(context)
					.reply(turnToken(TurnToken.Channel.WEB_WIDGET, "conv-1"), "Cuanto cuesta?");

			assertThat(reply.text()).isEqualTo("El masaje relajante cuesta $ 120.000.");
			assertThat(reply.promptVersion()).isEqualTo("customer-agent.v1");
			assertThat(reply.model()).isEqualTo("test-model");
			assertThat(reply.promptTokens()).isEqualTo(11);
			assertThat(reply.completionTokens()).isEqualTo(7);
		});
	}

	@Test
	@DisplayName("las herramientas del agente exponen listar, consultar y disponibilidad con su descripcion")
	void toolsExposeReadOnlyActions() {
		runner(10).run(context -> {
			ToolCallback[] callbacks = ToolCallbacks.from(context.getBean(CustomerTools.class));

			assertThat(callbacks).hasSize(3);
			assertThat(callbacks).extracting(callback -> callback.getToolDefinition().name())
					.containsExactlyInAnyOrder("listarServicios", "consultarServicio", "consultarDisponibilidad");
			assertThat(callbacks).allSatisfy(
					callback -> assertThat(callback.getToolDefinition().description()).isNotBlank());
		});
	}

	@Test
	@DisplayName("guarda el turno en memoria con el id namespaced por tenant y canal")
	void storesTurnInNamespacedMemory() {
		runner(10).run(context -> {
			agent(context).reply(turnToken(TurnToken.Channel.WEB_WIDGET, "conv-1"), "Hola, cuanto cuesta un masaje?");

			List<Message> messages = context.getBean(ChatMemory.class).get("kamerinos:WEB_WIDGET:conv-1");

			assertThat(messages).hasSize(2);
			assertThat(messages.get(0).getMessageType()).isEqualTo(MessageType.USER);
			assertThat(messages.get(0).getText()).isEqualTo("Hola, cuanto cuesta un masaje?");
			assertThat(messages.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
			assertThat(messages.get(1).getText()).isEqualTo("Claro, con gusto te ayudo.");
		});
	}

	@Test
	@DisplayName("la ventana de memoria descarta los mensajes mas antiguos")
	void memoryWindowDropsOldMessages() {
		runner(2).run(context -> {
			CustomerAgent agent = agent(context);
			agent.reply(turnToken(TurnToken.Channel.WEB_WIDGET, "conv-1"), "Primer mensaje");
			agent.reply(turnToken(TurnToken.Channel.WEB_WIDGET, "conv-1"), "Segundo mensaje");

			List<Message> messages = context.getBean(ChatMemory.class).get("kamerinos:WEB_WIDGET:conv-1");

			assertThat(messages).hasSize(2);
			assertThat(messages).extracting(Message::getText)
					.containsExactly("Segundo mensaje", "Claro, con gusto te ayudo.");
		});
	}

	@Test
	@DisplayName("aisla la memoria por canal y conversacion")
	void isolatesConversations() {
		runner(10).run(context -> {
			CustomerAgent agent = agent(context);
			agent.reply(turnToken(TurnToken.Channel.WEB_WIDGET, "conv-1"), "Mensaje del widget");
			agent.reply(turnToken(TurnToken.Channel.DASHBOARD, "conv-9"), "Mensaje del panel");

			ChatMemory memory = context.getBean(ChatMemory.class);
			assertThat(memory.get("kamerinos:WEB_WIDGET:conv-1")).extracting(Message::getText)
					.containsExactly("Mensaje del widget", "Claro, con gusto te ayudo.");
			assertThat(memory.get("kamerinos:DASHBOARD:conv-9")).extracting(Message::getText)
					.containsExactly("Mensaje del panel", "Claro, con gusto te ayudo.");
		});
	}

	@Test
	@DisplayName("el id de memoria sigue el formato tenant:canal:conversacion")
	void conversationIdIsNamespaced() {
		assertThat(CustomerAgent.conversationId(turnToken(TurnToken.Channel.WHATSAPP, "abc-123")))
				.isEqualTo("kamerinos:WHATSAPP:abc-123");
	}

	private CustomerAgent agent(AssertableApplicationContext context) {
		return new CustomerAgent(context.getBean(ChatClient.class), context.getBean(PromptTemplate.class),
				context.getBean(TenantProperties.class), CLOCK);
	}

	private static String systemText(Prompt prompt) {
		return prompt.getInstructions().stream()
				.filter(message -> message.getMessageType() == MessageType.SYSTEM)
				.map(Message::getText)
				.findFirst()
				.orElseThrow();
	}

	private static List<String> userTexts(Prompt prompt) {
		return prompt.getInstructions().stream()
				.filter(message -> message.getMessageType() == MessageType.USER)
				.map(Message::getText)
				.toList();
	}

	private static TurnToken turnToken(TurnToken.Channel channel, String conversationId) {
		return new TurnToken("turn-1", "kamerinos", conversationId, channel, TurnToken.Agent.CLIENTAS, null, null,
				Instant.now().plusSeconds(300), "turn-token-123");
	}
}
