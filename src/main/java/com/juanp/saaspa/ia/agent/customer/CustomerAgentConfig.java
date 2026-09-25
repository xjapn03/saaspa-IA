package com.juanp.saaspa.ia.agent.customer;

import java.util.Arrays;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.juanp.saaspa.ia.config.AgentProperties;
import com.juanp.saaspa.ia.config.TenantProperties;
import com.juanp.saaspa.ia.tools.CustomerTools;
import com.juanp.saaspa.ia.usage.LoggingToolCallback;
import com.juanp.saaspa.ia.usage.ToolCallLogger;

import tools.jackson.databind.ObjectMapper;

/**
 * Cableado del agente CLIENTAS.
 *
 * <p>La memoria usa el {@code ChatMemoryRepository} de Spring AI (JDBC sobre el esquema {@code ia},
 * cuya tabla crea Flyway V1; D-MEM) con la ventana configurada en {@code saaspa.agent.memory-window}.
 * Se declara aqui un {@code ChatMemory} propio a proposito: asi la ventana es explicita y no depende
 * de los valores por defecto de la autoconfiguracion.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentProperties.class)
public class CustomerAgentConfig {

	@Bean
	public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, AgentProperties agentProperties) {
		return MessageWindowChatMemory.builder()
				.chatMemoryRepository(chatMemoryRepository)
				.maxMessages(agentProperties.memoryWindow())
				.build();
	}

	@Bean
	public MessageChatMemoryAdvisor chatMemoryAdvisor(ChatMemory chatMemory) {
		return MessageChatMemoryAdvisor.builder(chatMemory).build();
	}

	@Bean
	public PromptTemplate customerSystemPrompt(AgentProperties agentProperties) {
		return new PromptTemplate(agentProperties.customerPrompt());
	}

	@Bean
	public ChatClient customerChatClient(ChatClient.Builder chatClientBuilder, CustomerTools customerTools,
			MessageChatMemoryAdvisor chatMemoryAdvisor, ToolCallLogger toolCallLogger, ObjectMapper objectMapper) {
		ToolCallback[] toolCallbacks = Arrays.stream(ToolCallbacks.from(customerTools))
				.map(callback -> new LoggingToolCallback(callback, toolCallLogger, objectMapper))
				.toArray(ToolCallback[]::new);
		// defaultTools acepta tanto POJOs con @Tool como instancias de ToolCallback (asi se registran
		// las envueltas para auditar); defaultToolCallbacks esta deprecado en Spring AI 2.0.
		return chatClientBuilder.defaultTools(toolCallbacks).defaultAdvisors(chatMemoryAdvisor).build();
	}

	@Bean
	public CustomerAgent customerAgent(ChatClient customerChatClient, PromptTemplate customerSystemPrompt,
			TenantProperties tenantProperties) {
		return new CustomerAgent(customerChatClient, customerSystemPrompt, tenantProperties);
	}
}
