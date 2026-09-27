package com.juanp.saaspa.ia.tenant;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.juanp.saaspa.ia.config.TenantProperties;

/**
 * Comprueba que el acople de tenant y zona horaria con NestJS siga alineado (J-06 y ADR 0016).
 *
 * <p>El <strong>tenant</strong> ya falla cerrado: si el del turn token no es el configurado se responde 403
 * (A-03). Lo que podia degradarse en silencio es la <strong>zona horaria</strong>: con
 * {@code TENANT_TIMEZONE} (backend) y {@code IA_TENANT_TIMEZONE} (este servicio) desalineados, las fechas
 * relativas del prompt y la disponibilidad se interpretan en zonas distintas y no hay ningun error visible.
 *
 * <p>NestJS ya envia su zona en cada turno (campo {@code timezone} del cuerpo, contexto informativo segun
 * C-02), asi que la comparacion se hace en el primer turno que llega: <strong>no necesita un endpoint nuevo
 * ni coordinacion con el otro repo</strong>. La autoridad sigue siendo la de este servicio (C-02): la zona
 * del cuerpo <strong>nunca</strong> se usa para el prompt ni para validar fechas, solo se compara.
 *
 * <p>El desacople se advierte <strong>una vez por instancia</strong> (no en cada turno) y <strong>no corta
 * el turno</strong>: el campo es informativo, asi que no hay razon para rechazar una peticion real por esto.
 */
public class TenantCouplingCheck {

	private static final Logger log = LoggerFactory.getLogger(TenantCouplingCheck.class);

	private final TenantProperties tenantProperties;

	private final AtomicBoolean warned = new AtomicBoolean();

	public TenantCouplingCheck(TenantProperties tenantProperties) {
		this.tenantProperties = tenantProperties;
	}

	/**
	 * @param bodyTimeZone zona horaria que envio NestJS en el cuerpo (puede ser {@code null})
	 * @return {@code true} si no hay nada que comparar o si coinciden
	 */
	public boolean isCoupled(String bodyTimeZone) {
		return bodyTimeZone == null || this.tenantProperties.timeZone().equals(bodyTimeZone);
	}

	/**
	 * Compara la zona del cuerpo con la configurada y, si no coinciden, lo advierte una sola vez.
	 *
	 * @param bodyTimeZone zona horaria que envio NestJS en el cuerpo (puede ser {@code null})
	 */
	public void check(String bodyTimeZone) {
		if (isCoupled(bodyTimeZone) || !this.warned.compareAndSet(false, true)) {
			return;
		}
		log.warn("Acople desalineado de zona horaria: NestJS envio '{}' (TENANT_TIMEZONE) y este servicio usa '{}' "
				+ "(IA_TENANT_TIMEZONE). Manda la del tenant (C-02) y el turno continua: revisar el despliegue",
				bodyTimeZone, this.tenantProperties.timeZone());
	}

}
