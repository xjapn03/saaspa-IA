package com.juanp.saaspa.ia.config;

import java.time.DateTimeException;
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

	/**
	 * Valida la configuracion <strong>al arrancar</strong> (J-06 y ADR 0016): un tenant vacio o una zona
	 * horaria que no sea un {@link ZoneId} valido (una errata, por ejemplo) fallan aqui con un mensaje claro,
	 * en vez de reventar con un 500 en el primer turno, que es lo que ocurriria al construir el prompt.
	 */
	public TenantProperties {
		if (defaultTenant == null || defaultTenant.isBlank()) {
			throw new IllegalArgumentException(
					"saaspa.tenant.default (IA_TENANT_DEFAULT) no puede estar vacio: no se atenderia ningun turno");
		}
		try {
			ZoneId.of(timeZone == null ? "" : timeZone);
		}
		catch (DateTimeException ex) {
			// Sin encadenar la causa a proposito: el analizador de fallos de Spring muestra la causa RAIZ, y
			// con la DateTimeException encadenada el mensaje que ve el operador no nombraba la variable de
			// entorno (comprobado arrancando con 'America/Bogotá'). El valor invalido va en el mensaje.
			throw new IllegalArgumentException("saaspa.tenant.timezone (IA_TENANT_TIMEZONE) no es una zona horaria "
					+ "valida: " + timeZone);
		}
	}

	/** @return la zona horaria del negocio como {@link ZoneId} */
	public ZoneId zoneId() {
		return ZoneId.of(this.timeZone);
	}
}
