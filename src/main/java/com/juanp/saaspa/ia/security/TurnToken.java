package com.juanp.saaspa.ia.security;

import java.time.Instant;

/**
 * Identidad de un turno, extraida del turn token ya verificado (ADR 0006).
 *
 * <p>Regla R1 de AGENTS.md: estos datos <strong>nunca</strong> salen de argumentos generados por el
 * modelo; vienen del token firmado por NestJS.
 *
 * @param turnId identificador del turno (claim {@code jti})
 * @param tenantId tenant que atendio la peticion
 * @param conversationId conversacion dentro del tenant
 * @param channel canal de origen
 * @param agent agente que debe atender
 * @param userId usuario autenticado, si lo hay
 * @param role rol del usuario, si lo hay
 * @param clientIp IP del origen resuelta por el backend con su proxy de confianza (claim opcional, ADR 0020)
 * @param expiresAt caducidad del token
 * @param rawToken token original, que se reenvia tal cual a la API interna del backend
 */
public record TurnToken(
		String turnId,
		String tenantId,
		String conversationId,
		Channel channel,
		Agent agent,
		String userId,
		Role role,
		String clientIp,
		Instant expiresAt,
		String rawToken) {

	/** Canales soportados por el contrato de chat. */
	public enum Channel {

		/** WhatsApp Cloud API (Fase 4). */
		WHATSAPP,

		/** Widget web anonimo. */
		WEB_WIDGET,

		/** Chat web con sesion iniciada. */
		WEB_LOGGED,

		/** Chat del dashboard (empleado o administrador). */
		DASHBOARD

	}

	/** Agente que debe atender el turno. */
	public enum Agent {

		/** Agente de clientas: solo datos propios y catalogo publico (R3). */
		CLIENTAS,

		/** Agente de administracion: solo lectura (R4). */
		ADMIN

	}

	/** Roles del backend. */
	public enum Role {

		/** Clienta final. */
		CLIENTE,

		/** Personal de la sede. */
		EMPLEADO,

		/** Administracion. */
		ADMIN

	}

	/** @return {@code true} si el turno viene de una visitante anonima */
	public boolean isAnonymous() {
		return this.userId == null;
	}

	/**
	 * Clave de origen del turno para el tope de coste por origen (ADR 0020, hallazgo H-04): el usuario
	 * cuando el turno esta identificado y la IP resuelta por el backend cuando es anonimo.
	 *
	 * <p>Es la unica clave de origen que <strong>no se puede rotar</strong>: el {@code conversationId} y la
	 * sesion anonima si se pueden descartar (y con ellos se reiniciaba su tope). Cada fuente lleva su
	 * prefijo ({@code user:} / {@code ip:}) para que un id de usuario no pueda colisionar con una IP.
	 *
	 * @return clave de origen, o {@code null} si el token no trae ni usuario ni IP (claim de H-04 aun sin
	 * emitir por el backend: entonces el turno se registra sin origen y no se le aplica el tope por origen)
	 */
	public String origin() {
		if (this.userId != null) {
			return "user:" + this.userId;
		}
		return this.clientIp == null ? null : "ip:" + this.clientIp;
	}
}
