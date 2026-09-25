package com.juanp.saaspa.ia.agent.handoff;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registra la politica de handoff, que decide en codigo cuando hay que pasar la conversacion a una
 * persona (regla R10).
 */
@Configuration(proxyBeanMethods = false)
public class HandoffConfig {

	@Bean
	public HandoffPolicy handoffPolicy() {
		return new HandoffPolicy();
	}
}
