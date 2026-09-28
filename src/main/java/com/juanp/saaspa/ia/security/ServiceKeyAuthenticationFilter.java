package com.juanp.saaspa.ia.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Exige la clave de servicio de entrada antes de evaluar el turn token.
 *
 * <p>Se ejecuta delante de {@code BearerTokenAuthenticationFilter}: sin clave valida no se intenta
 * verificar ningun token. Si la clave no esta configurada, rechaza la peticion (fallo cerrado).
 *
 * <p>El motivo del rechazo se registra aqui (ADR 0017): saber si la clave falta o no esta configurada es
 * diagnostico, no algo que deba leer la clienta ni distinguir quien llama.
 */
public class ServiceKeyAuthenticationFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(ServiceKeyAuthenticationFilter.class);

	private final ServiceKeyVerifier verifier;

	private final ProblemDetailSecurityHandler handler;

	public ServiceKeyAuthenticationFilter(ServiceKeyVerifier verifier, ProblemDetailSecurityHandler handler) {
		this.verifier = verifier;
		this.handler = handler;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (!this.verifier.isValid(request.getHeader(ServiceKeyVerifier.HEADER))) {
			if (this.verifier.isConfigured()) {
				log.warn("Peticion rechazada: clave de servicio ausente o invalida");
			}
			else {
				// Fallo cerrado por despliegue: sin IA_BOT_API_KEY no se atiende ningun turno.
				log.error("Peticion rechazada: el servicio no tiene configurada la clave de servicio de entrada");
			}
			this.handler.unauthorized(response);
			return;
		}
		filterChain.doFilter(request, response);
	}
}
