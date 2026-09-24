package com.juanp.saaspa.ia.tools;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.juanp.saaspa.ia.backend.BackendClient;
import com.juanp.saaspa.ia.config.TenantProperties;

/**
 * Registra las herramientas del agente CLIENTAS (T1.3) para poder pasarlas al {@code ChatClient}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TenantProperties.class)
public class ToolsConfig {

	@Bean
	public CustomerTools customerTools(BackendClient backendClient, TenantProperties tenantProperties) {
		return new CustomerTools(backendClient, tenantProperties);
	}
}
