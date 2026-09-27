package com.juanp.saaspa.ia.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracion del cliente HTTP hacia el modelo (DeepSeek) y del deadline del turno.
 *
 * <p>Los timeouts son explicitos (AGENTS.md, seccion 8) y van aparte del cliente del backend, que ya
 * tiene los suyos ({@code saaspa.backend.*}).
 *
 * <p><strong>Escalera end-to-end (ADR 0014):</strong> el deadline del turno queda por debajo del timeout
 * con el que NestJS llama a este servicio ({@code IA_BOT_TIMEOUT_MS}, 25 s), que es el tope que ve la
 * clienta, y el timeout de lectura del modelo queda por debajo del deadline. Valores por defecto:
 * lectura 10 s y deadline 20 s.
 *
 * @param connectTimeout timeout de establecimiento de conexion con el proveedor del modelo
 * @param readTimeout timeout de espera de la respuesta del modelo
 * @param turnDeadline tope total del turno conversacional (llamada al modelo + herramientas)
 */
@ConfigurationProperties("saaspa.llm")
public record LlmProperties(
		@DefaultValue("3s") Duration connectTimeout,
		@DefaultValue("10s") Duration readTimeout,
		@DefaultValue("20s") Duration turnDeadline) {
}