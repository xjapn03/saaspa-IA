package com.juanp.saaspa.ia.config;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Contexto de tenant del servicio.
 *
 * <p>El backend NestJS todavia no tiene multi-tenancy real (T1.0): el tenant efectivo es el
 * despliegue. Estos valores se usan para el prompt (fecha y zona horaria, R13) y para validar
 * fechas relativas en las herramientas.
 *
 * @param defaultTenant tenant del piloto ({@code kamerinos})
 * @param displayName nombre visible del negocio para los prompts
 * @param timeZone zona horaria del negocio, en formato {@link ZoneId}
 */
@ConfigurationProperties("saaspa.tenant")
public record TenantProperties(
		@DefaultValue("kamerinos") String defaultTenant,
		@DefaultValue("Kamerinos SPA Bogota") String displayName,
		@DefaultValue("America/Bogota") String timeZone) {

	/** @return la zona horaria del negocio como {@link ZoneId} */
	public ZoneId zoneId() {
		return ZoneId.of(this.timeZone);
	}
}
