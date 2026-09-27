package com.juanp.saaspa.ia.tenant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.juanp.saaspa.ia.config.AgentProperties;
import com.juanp.saaspa.ia.config.TenantProperties;

/**
 * Acople de tenant y zona horaria con NestJS (J-06 y ADR 0016): la comprobacion que se hace en el primer
 * turno y los valores publicados en {@code /actuator/info} como punto de comparacion.
 *
 * <p>Los dos beans viven juntos porque son las dos mitades de la misma decision: uno detecta el desacople en
 * caliente y el otro hace que sea consultable desde fuera.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ TenantProperties.class, AgentProperties.class })
public class TenantCouplingConfig {

	/**
	 * @param tenantProperties tenant y zona horaria del despliegue
	 * @return el comprobador que se invoca en cada turno (avisa una vez, nunca corta)
	 */
	@Bean
	public TenantCouplingCheck tenantCouplingCheck(TenantProperties tenantProperties) {
		return new TenantCouplingCheck(tenantProperties);
	}

	/**
	 * @param tenantProperties tenant y zona horaria del despliegue
	 * @param agentProperties prompt del agente (el nombre del fichero lleva su version)
	 * @param model modelo configurado para el agente
	 * @return el contribuidor de {@code /actuator/info} con el contexto del tenant
	 */
	@Bean
	public InfoContributor tenantInfoContributor(TenantProperties tenantProperties, AgentProperties agentProperties,
			@Value("${spring.ai.deepseek.chat.options.model:unknown}") String model) {
		return new TenantInfoContributor(tenantProperties, agentProperties, model);
	}

}
