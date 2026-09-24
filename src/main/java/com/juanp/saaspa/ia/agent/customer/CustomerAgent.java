package com.juanp.saaspa.ia.agent.customer;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;

import com.juanp.saaspa.ia.config.TenantProperties;
import com.juanp.saaspa.ia.security.TurnToken;

/**
 * Agente CLIENTAS de la Fase 1: catalogo, precios y disponibilidad.
 *
 * <p>Responsabilidades y limites:
 * <ul>
 *   <li>usa el prompt versionado en es-CO y le inyecta la fecha de hoy y la zona horaria del negocio
 *       (R13), de modo que el modelo pueda resolver fechas relativas;
 *   <li>la identidad sale siempre del turn token; el id de memoria va namespaced
 *       {@code {tenantId}:{channel}:{conversationId}} (D-MEM, regla R5);
 *   <li>las herramientas registradas por defecto son las de solo lectura de {@code CustomerTools};
 *   <li>devuelve tambien el modelo y los tokens usados para que el registro del turno (T1.6) no
 *       dependa del modelo.
 * </ul>
 *
 * <p>Ningun test de esta clase llama a un LLM real (R14): se usa un {@code ChatModel} doble.
 */
public class CustomerAgent {

	/** Version del prompt del agente; se registra en cada turno (T1.6). */
	public static final String PROMPT_VERSION = "customer-agent.v1";

	private static final Locale LOCALE_CO = Locale.forLanguageTag("es-CO");

	private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

	private static final DateTimeFormatter TEXT_DATE = DateTimeFormatter
			.ofPattern("EEEE d 'de' MMMM 'de' yyyy", LOCALE_CO);

	private final ChatClient chatClient;

	private final PromptTemplate systemPrompt;

	private final TenantProperties tenantProperties;

	private final Clock clock;

	public CustomerAgent(ChatClient chatClient, PromptTemplate systemPrompt, TenantProperties tenantProperties) {
		this(chatClient, systemPrompt, tenantProperties, Clock.systemDefaultZone());
	}

	CustomerAgent(ChatClient chatClient, PromptTemplate systemPrompt, TenantProperties tenantProperties, Clock clock) {
		this.chatClient = chatClient;
		this.systemPrompt = systemPrompt;
		this.tenantProperties = tenantProperties;
		this.clock = clock;
	}

	/**
	 * Responde un turno de la clienta.
	 *
	 * @param turnToken identidad del turno ya verificada
	 * @param userMessage mensaje de la clienta
	 * @return texto de la respuesta y datos de uso del modelo
	 */
	public CustomerReply reply(TurnToken turnToken, String userMessage) {
		ChatResponse response = this.chatClient.prompt()
				.system(this.systemPrompt.render(systemParams()))
				.user(userMessage)
				.advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, conversationId(turnToken)))
				.call()
				.chatResponse();
		return toReply(response);
	}

	/**
	 * Id de memoria namespaced por tenant y canal (D-MEM).
	 *
	 * @param turnToken identidad del turno
	 * @return identificador de conversacion para la memoria
	 */
	static String conversationId(TurnToken turnToken) {
		return "%s:%s:%s".formatted(turnToken.tenantId(), turnToken.channel().name(), turnToken.conversationId());
	}

	private Map<String, Object> systemParams() {
		LocalDate today = LocalDate.now(this.clock.withZone(this.tenantProperties.zoneId()));
		return Map.of("tenant", this.tenantProperties.displayName(), "fechaActual", today.format(ISO_DATE),
				"fechaActualTexto", today.format(TEXT_DATE), "zonaHoraria", this.tenantProperties.timeZone());
	}

	private static CustomerReply toReply(ChatResponse response) {
		String text = response.getResult() == null ? null : response.getResult().getOutput().getText();
		ChatResponseMetadata metadata = response.getMetadata();
		Usage usage = metadata == null ? null : metadata.getUsage();
		return new CustomerReply(text, PROMPT_VERSION, metadata == null ? null : metadata.getModel(),
				usage == null ? null : usage.getPromptTokens(), usage == null ? null : usage.getCompletionTokens());
	}

	/**
	 * Respuesta del agente con datos de uso.
	 *
	 * @param text texto para la clienta (puede ser {@code null} si el modelo no devolvio texto)
	 * @param promptVersion version del prompt usada
	 * @param model modelo que respondio
	 * @param promptTokens tokens de entrada informados por el proveedor
	 * @param completionTokens tokens de salida informados por el proveedor
	 */
	public record CustomerReply(String text, String promptVersion, String model, Integer promptTokens,
			Integer completionTokens) {
	}
}
