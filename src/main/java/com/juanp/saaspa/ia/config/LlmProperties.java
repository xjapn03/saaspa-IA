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
 * @param connectTimeout timeout de establecimiento de conexion con el proveedor del modelo
 * @param readTimeout timeout de espera de la respuesta del modelo
 * @param turnDeadline tope total del turno conversacional (llamada al modelo + herramientas)
 */
@ConfigurationProperties("saaspa.llm")
public record LlmProperties(
		@DefaultValue("3s") Duration connectTimeout,
		@DefaultValue("30s") Duration readTimeout,
		@DefaultValue("35s") Duration turnDeadline) {
}