package com.juanp.saaspa.ia.backend;

/**
 * Fabricas de errores del backend para los tests de otros paquetes.
 *
 * <p>Los constructores de {@link BackendException} son de paquete a proposito (el cliente es el unico que
 * debe crearlos), asi que desde aqui -el mismo paquete- se puede construir el error "con respuesta HTTP no
 * exitosa", que es el que no se puede simular con {@link BackendUnavailableException}.
 */
public final class TestBackendExceptions {

	private TestBackendExceptions() {
	}

	/**
	 * @return error de backend con respuesta HTTP no exitosa (equivalente a un 5xx del backend)
	 */
	public static BackendException errorResponse() {
		return BackendException.fromStatus(500, "/api/internal/v1/services", "boom");
	}

}
