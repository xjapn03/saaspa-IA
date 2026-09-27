package com.juanp.saaspa.ia.usage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.juanp.saaspa.ia.config.CostGuardProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * Registro de uso y auditoria (esquema propio {@code ia}, tablas de Flyway V1) y tope de coste por tenant
 * y por conversacion (ADR 0010).
 *
 * <p>Los servicios se declaran como beans para que la capa de API registre el turno y las
 * herramientas queden envueltas por {@link LoggingToolCallback} al registrarlas en el {@code ChatClient}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CostGuardProperties.class)
public class UsageConfig {

	@Bean
	public TurnLogService turnLogService(JdbcTemplate jdbcTemplate) {
		return new TurnLogService(jdbcTemplate);
	}

	/**
	 * @param jdbcTemplate acceso al esquema {@code ia} (fuente de verdad del consumo)
	 * @param costGuardProperties topes configurables por entorno
	 * @return el guardia de coste que se evalua antes de llamar al modelo
	 */
	@Bean
	public TurnCostGuard turnCostGuard(JdbcTemplate jdbcTemplate, CostGuardProperties costGuardProperties) {
		return new TurnCostGuard(jdbcTemplate, costGuardProperties);
	}

	/**
	 * @param costGuardProperties configuracion del guardia (incluye la sal del hash de origen)
	 * @return el hasheador con el que el turno guarda su origen sin almacenar la IP (ADR 0020)
	 */
	@Bean
	public OriginHasher originHasher(CostGuardProperties costGuardProperties) {
		return new OriginHasher(costGuardProperties.originSalt());
	}

	@Bean
	public ToolCallLogger toolCallLogger(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		return new ToolCallLogger(jdbcTemplate, objectMapper);
	}
}
