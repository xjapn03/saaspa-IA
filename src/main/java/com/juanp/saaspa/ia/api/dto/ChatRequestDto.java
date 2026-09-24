package com.juanp.saaspa.ia.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.juanp.saaspa.ia.security.TurnToken;

/**
 * Turno que NestJS envia a este servicio ({@code POST /api/v1/chat}, contrato chat-api).
 *
 * <p>Los campos de contexto (tenant, conversacion, canal, agente e identidad) se contrastan contra los
 * claims del turn token: el token manda (regla R1). Ver {@code TurnContextValidator}.
 *
 * @param turnId identificador del turno; debe coincidir con el {@code jti} del turn token
 * @param tenantId tenant que atendio la peticion
 * @param conversationId conversacion dentro del tenant
 * @param channel canal de origen
 * @param agent agente que debe atender (Fase 1: solo CLIENTAS)
 * @param identity identidad resuelta por NestJS
 * @param message mensaje de la clienta
 * @param locale idioma de la conversacion (por ejemplo {@code es-CO})
 * @param timezone zona horaria del negocio
 * @param now instante del turno segun NestJS
 */
public record ChatRequestDto(
		@NotNull UUID turnId,
		@NotBlank @Size(max = 64) String tenantId,
		@NotBlank @Size(max = 128) String conversationId,
		@NotNull TurnToken.Channel channel,
		@NotNull TurnToken.Agent agent,
		@NotNull @Valid Identity identity,
		@NotNull @Valid Message message,
		@NotBlank @Size(max = 16) String locale,
		@NotBlank @Size(max = 64) String timezone,
		@NotNull OffsetDateTime now) {

	/** Tipo de identidad del turno. */
	public enum IdentityKind {

		/** Visitante sin sesion (widget web o WhatsApp sin usuario asociado). */
		ANONYMOUS,

		/** Persona con sesion o usuario resuelto. */
		USER

	}

	/**
	 * Identidad resuelta por NestJS.
	 *
	 * @param kind tipo de identidad
	 * @param userId identificador del usuario, solo si {@code kind = USER}
	 * @param role rol del usuario, solo si {@code kind = USER}
	 * @param waId identificador de WhatsApp, solo en ese canal
	 */
	public record Identity(@NotNull IdentityKind kind, @Size(max = 64) String userId, TurnToken.Role role,
			@Size(max = 32) String waId) {
	}

	/**
	 * Mensaje de la clienta.
	 *
	 * @param text texto del mensaje
	 */
	public record Message(@NotBlank @Size(max = 2000) String text) {
	}
}
