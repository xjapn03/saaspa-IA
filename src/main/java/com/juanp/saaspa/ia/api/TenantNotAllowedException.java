package com.juanp.saaspa.ia.api;

/**
 * El {@code tenantId} del turn token no esta en la lista permitida del servicio
 * ({@code saaspa.tenant.default}, alimentado por {@code IA_TENANT_DEFAULT}).
 *
 * <p>La capa de API lo traduce a un {@code ProblemDetail} 403: fallo cerrado.
 */
public class TenantNotAllowedException extends RuntimeException {

	public TenantNotAllowedException(String message) {
		super(message);
	}
}