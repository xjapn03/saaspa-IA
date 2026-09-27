package com.juanp.saaspa.ia.tenant;

import java.util.Map;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

import com.juanp.saaspa.ia.config.AgentProperties;
import com.juanp.saaspa.ia.config.TenantProperties;

/**
 * Publica en {@code /actuator/info} el contexto con el que este servicio atiende los turnos (J-06 y
 * ADR 0016): tenant, zona horaria, prompt y modelo.
 *
 * <p>Es el punto de comparacion para el otro lado y para operacion: los dos valores que pueden desalinearse
 * en el despliegue (tenant y zona horaria) quedan consultables sin abrir la base ni los logs, y de paso
 * {@code /actuator/info} deja de estar vacio (hallazgo A-13). El endpoint vive en la red interna
 * ({@code ia-bot} no publica puertos) y no revela ningun secreto: tenant, zona, version del prompt y nombre
 * del modelo son configuracion.
 *
 * <p><strong>Limite:</strong> esto publica lo que cree este servicio, no lo que tiene el backend; la
 * comprobacion efectiva la hace {@link TenantCouplingCheck} con la zona que llega en cada turno.
 */
public class TenantInfoContributor implements InfoContributor {

	private final TenantProperties tenantProperties;

	private final AgentProperties agentProperties;

	private final String model;

	public TenantInfoContributor(TenantProperties tenantProperties, AgentProperties agentProperties, String model) {
		this.tenantProperties = tenantProperties;
		this.agentProperties = agentProperties;
		this.model = model;
	}

	@Override
	public void contribute(Info.Builder builder) {
		builder.withDetail("saaspa", Map.of(
				"tenant",
				Map.of("id", this.tenantProperties.defaultTenant(), "timezone", this.tenantProperties.timeZone()),
				"agent", Map.of("prompt", promptName()),
				"llm", Map.of("model", this.model)));
	}

	private String promptName() {
		String filename = this.agentProperties.customerPrompt() == null ? null
				: this.agentProperties.customerPrompt().getFilename();
		return filename == null ? "unknown" : filename;
	}

}
