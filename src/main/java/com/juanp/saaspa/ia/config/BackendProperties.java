package com.juanp.saaspa.ia.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracion del cliente HTTP hacia saaspa-backend (API interna {@code /api/internal/v1/*}).
 *
 * <p>Los timeouts se declaran de forma explicita (AGENTS.md, seccion 8) y son configurables por
 * entorno: {@code BACKEND_CONNECT_TIMEOUT} y {@code BACKEND_READ_TIMEOUT}.
 *
 * @param baseUrl URL base del backend, por ejemplo {@code http://backend:3001}
 * @param internalApiKey clave de servicio en la direccion IA -&gt; NestJS ({@code INTERNAL_API_KEY}).
 *     Es un secreto distinto del que usa NestJS para llamar a este servicio
 *     ({@code IA_BOT_API_KEY}); puede estar vacia en desarrollo local.
 * @param connectTimeout timeout de establecimiento de conexion
 * @param readTimeout timeout de espera de la respuesta
 */
@ConfigurationProperties("saaspa.backend")
public record BackendProperties(
		@DefaultValue("http://localhost:3001") String baseUrl,
		@DefaultValue("") String internalApiKey,
		@DefaultValue("1s") Duration connectTimeout,
		@DefaultValue("5s") Duration readTimeout) {
}
