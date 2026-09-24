package com.juanp.saaspa.ia.security;

import java.util.Optional;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Acceso al turn token verificado del turno en curso.
 *
 * <p>Es la unica via por la que el resto del servicio obtiene tenant, usuario, rol, canal y agente,
 * y de donde las herramientas toman el token que deben reenviar al backend (reglas R1 y R2). Nunca
 * se aceptan estos datos desde argumentos del modelo ni desde el cuerpo de la peticion.
 */
public final class CurrentTurnToken {

	private CurrentTurnToken() {
	}

	/** @return el turn token del contexto, si la peticion traia uno verificado */
	public static Optional<TurnToken> find() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof TurnToken turnToken) {
			return Optional.of(turnToken);
		}
		return Optional.empty();
	}

	/**
	 * @return el turn token del contexto
	 * @throws AuthenticationCredentialsNotFoundException si no hay ninguno verificado
	 */
	public static TurnToken require() {
		return find().orElseThrow(
				() -> new AuthenticationCredentialsNotFoundException("No hay turn token verificado en el contexto"));
	}
}
