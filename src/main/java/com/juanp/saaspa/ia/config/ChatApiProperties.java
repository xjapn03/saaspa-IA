package com.juanp.saaspa.ia.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracion de la API de chat que NestJS llama en este servicio (direccion NestJS -&gt; IA).
 *
 * <p>La clave de servicio es un secreto <strong>distinto</strong> del que este servicio envia al
 * backend ({@link BackendProperties#internalApiKey()}): dos secretos, uno por direccion
 * (AGENTS.md, seccion 11).
 *
 * @param serviceKey valor esperado de la cabecera {@code X-Internal-Api-Key} (env
 *     {@code IA_BOT_API_KEY}); vacio desactiva toda peticion entrante (fallo cerrado)
 */
@ConfigurationProperties("saaspa.chat-api")
public record ChatApiProperties(@DefaultValue("") String serviceKey) {
}
