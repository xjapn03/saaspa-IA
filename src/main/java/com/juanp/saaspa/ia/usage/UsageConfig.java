package com.juanp.saaspa.ia.usage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

/**
 * Registro de uso y auditoria (esquema propio {@code ia}, tablas de Flyway V1).
 *
 * <p>Los servicios se declaran como beans para que la capa de API registre el turno y las
 * herramientas queden envueltas por {@link LoggingToolCallback} al registrarlas en el {@code ChatClient}.
 */
@Configuration(proxyBeanMethods = false)
public class UsageConfig {

	@Bean
	public TurnLogService turnLogService(JdbcTemplate jdbcTemplate) {
		return new TurnLogService(jdbcTemplate);
	}

	@Bean
	public ToolCallLogger toolCallLogger(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		return new ToolCallLogger(jdbcTemplate, objectMapper);
	}
}
