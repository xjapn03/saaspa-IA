package com.juanp.saaspa.ia.api;

import org.springframework.http.HttpStatus;

/**
 * Catalogo cerrado de errores que este servicio devuelve a NestJS (ADR 0017).
 *
 * <p>Es la <strong>frontera entre el diagnostico interno y lo que lee una persona</strong>: el
 * {@code detail} de cada codigo es el texto que el backend reenvia al widget y, por tanto, lo que ve una
 * clienta. El motivo tecnico (que campo no cuadro, que fallo la clave de servicio, que excepcion se lanzo)
 * se registra en el log y <strong>nunca</strong> viaja en el cuerpo de la respuesta (R8).
 *
 * <p>El codigo viaja ademas como propiedad {@code code}, estable y sin PII, para que el gateway pueda
 * decidir por codigo en vez de por prosa (J-07) y para los casos de conformidad entre repos (J-13).
 *
 * <p>{@code ProblemCodeTest} comprueba que ningun texto publico contiene jerga interna ni referencias al
 * roadmap: es la guarda que impide que vuelva a colarse un mensaje de diagnostico hacia la clienta.
 */
public enum ProblemCode {

	/** El cuerpo no coincide con los claims del turn token (regla R1). */
	TURN_CONTEXT_MISMATCH(HttpStatus.BAD_REQUEST, "Peticion invalida",
			"No pudimos identificar tu conversacion; recarga la pagina e intenta de nuevo"),

	/** El cuerpo no pasa la validacion de forma. */
	INVALID_BODY(HttpStatus.BAD_REQUEST, "Peticion invalida", "El cuerpo de la peticion no es valido"),

	/** El cuerpo no es JSON legible. */
	MALFORMED_BODY(HttpStatus.BAD_REQUEST, "Peticion invalida", "El cuerpo de la peticion no es valido"),

	/** Falta la clave de servicio o el turn token, o no son validos. */
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "No autorizado",
			"No pudimos validar tu sesion de chat; recarga la pagina e intenta de nuevo"),

	/** El turn token no permite la operacion pedida. */
	ACCESS_DENIED(HttpStatus.FORBIDDEN, "Acceso denegado",
			"No pudimos validar tu sesion de chat; recarga la pagina e intenta de nuevo"),

	/** El tenant del turn token no es el configurado en este despliegue (A-03). */
	TENANT_NOT_ALLOWED(HttpStatus.FORBIDDEN, "Peticion no permitida",
			"No pudimos validar tu sesion de chat; recarga la pagina e intenta de nuevo"),

	/** El turno supero un tope de coste (ADR 0010 y ADR 0020). */
	COST_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "Limite de uso alcanzado",
			"Se alcanzo el limite de uso de este asistente; intenta de nuevo mas tarde"),

	/** El agente pedido todavia no existe (Fase 1: solo CLIENTAS). */
	AGENT_NOT_IMPLEMENTED(HttpStatus.NOT_IMPLEMENTED, "No implementado",
			"El asistente no puede atender esa consulta todavia"),

	/** El backend de agenda no respondio (timeout, conexion rechazada). */
	BACKEND_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "Sistema de agenda no disponible",
			"El sistema de agenda no respondio; intenta de nuevo en un momento"),

	/** El backend de agenda respondio con un error. */
	BACKEND_ERROR(HttpStatus.BAD_GATEWAY, "Sistema de agenda no disponible",
			"El sistema de agenda no pudo responder la consulta"),

	/** El modelo no respondio dentro del deadline del turno (ADR 0009 y ADR 0014). */
	MODEL_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Modelo no disponible",
			"El modelo no respondio a tiempo; intenta de nuevo en un momento"),

	/** Cualquier fallo inesperado atendiendo el turno. */
	UNEXPECTED(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno",
			"Ocurrio un error inesperado atendiendo el turno");

	private final HttpStatus status;

	private final String title;

	private final String publicDetail;

	ProblemCode(HttpStatus status, String title, String publicDetail) {
		this.status = status;
		this.title = title;
		this.publicDetail = publicDetail;
	}

	/** @return estado HTTP con el que se responde este error */
	public HttpStatus status() {
		return this.status;
	}

	/** @return resumen humano corto (propiedad {@code title} de RFC 9457) */
	public String title() {
		return this.title;
	}

	/** @return texto apto para la clienta (propiedad {@code detail}), nunca el motivo tecnico */
	public String publicDetail() {
		return this.publicDetail;
	}

}
