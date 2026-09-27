package com.juanp.saaspa.ia.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validacion de la configuracion de tenant <strong>al arrancar</strong> (J-06 y ADR 0016): con una errata en
 * la zona horaria el servicio antes arrancaba y fallaba con un 500 en el primer turno.
 */
class TenantPropertiesTest {

	@Test
	@DisplayName("acepta una zona horaria valida")
	void acceptsAValidZone() {
		assertThat(new TenantProperties("kamerinos", "Kamerinos SPA Bogota", "America/Bogota").zoneId())
				.isEqualTo(ZoneId.of("America/Bogota"));
	}

	@Test
	@DisplayName("una zona horaria con errata falla al construir la configuracion")
	void rejectsAnInvalidZone() {
		assertThatThrownBy(() -> new TenantProperties("kamerinos", "Kamerinos SPA Bogota", "America/Bogotá"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("IA_TENANT_TIMEZONE");
	}

	@Test
	@DisplayName("un tenant vacio falla al construir la configuracion")
	void rejectsABlankTenant() {
		assertThatThrownBy(() -> new TenantProperties(" ", "Kamerinos SPA Bogota", "America/Bogota"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("IA_TENANT_DEFAULT");
	}

}
