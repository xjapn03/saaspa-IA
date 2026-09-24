package com.juanp.saaspa.ia.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

/**
 * Configuracion del agente conversacional.
 *
 * @param memoryWindow ventana de memoria: cuantos mensajes (usuario y asistente) se envian al modelo
 * @param customerPrompt prompt de sistema del agente CLIENTAS, versionado y en es-CO
 */
@ConfigurationProperties("saaspa.agent")
public record AgentProperties(
		@DefaultValue("10") int memoryWindow,
		@DefaultValue("classpath:prompts/customer-agent.v1.md") Resource customerPrompt) {
}
