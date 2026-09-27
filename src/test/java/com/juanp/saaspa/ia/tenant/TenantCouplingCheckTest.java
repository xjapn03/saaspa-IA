package com.juanp.saaspa.ia.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.juanp.saaspa.ia.config.TenantProperties;

/**
 * Acople de zona horaria con NestJS (J-06 y ADR 0016): la comparacion avisa una sola vez y nunca cambia el
 * resultado del turno. El desacople vivia en el despliegue (las dos variables del compose) y no habia nada
 * que lo detectara.
 */
@ExtendWith(OutputCaptureExtension.class)
class TenantCouplingCheckTest {

	@Test
	@DisplayName("avisa una sola vez cuando la zona del backend no coincide con la del tenant (J-06)")
	void warnsOnceWhenTheZoneDoesNotMatch(CapturedOutput output) {
		TenantCouplingCheck check = new TenantCouplingCheck(properties("America/Bogota"));

		check.check("America/Lima");
		check.check("America/Lima");
		check.check("America/Lima");

		assertThat(output.toString()).containsOnlyOnce("Acople desalineado de zona horaria")
				.contains("America/Lima")
				.contains("America/Bogota")
				.contains("IA_TENANT_TIMEZONE");
	}

	@Test
	@DisplayName("no dice nada cuando coinciden")
	void staysQuietWhenTheyMatch(CapturedOutput output) {
		TenantCouplingCheck check = new TenantCouplingCheck(properties("America/Bogota"));

		check.check("America/Bogota");

		assertThat(output.toString()).doesNotContain("desalineado");
	}

	@Test
	@DisplayName("sin valor que comparar no avisa ni falla")
	void toleratesAMissingZone(CapturedOutput output) {
		TenantCouplingCheck check = new TenantCouplingCheck(properties("America/Bogota"));

		check.check(null);

		assertThat(output.toString()).doesNotContain("desalineado");
	}

	@Test
	@DisplayName("el predicado marca el acople alineado solo cuando coincide")
	void reportsTheCoupling() {
		TenantCouplingCheck check = new TenantCouplingCheck(properties("America/Bogota"));

		assertThat(check.isCoupled("America/Bogota")).isTrue();
		assertThat(check.isCoupled(null)).isTrue();
		assertThat(check.isCoupled("America/Lima")).isFalse();
	}

	private static TenantProperties properties(String timeZone) {
		return new TenantProperties("kamerinos", "Kamerinos SPA Bogota", timeZone);
	}

}
