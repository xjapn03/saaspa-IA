package com.juanp.saaspa.ia.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Verifica la clave de servicio con la que NestJS llama a este servicio (cabecera
 * {@code X-Internal-Api-Key}, valor de {@code IA_BOT_API_KEY}).
 *
 * <p>Es un secreto distinto del que este servicio envia al backend ({@code INTERNAL_API_KEY}): dos
 * secretos, uno por direccion (AGENTS.md, seccion 11).
 *
 * <p>La comparacion es en tiempo constante ({@link MessageDigest#isEqual}). Si no hay clave
 * configurada, la verificacion <strong>falla cerrada</strong>: se rechaza toda peticion.
 */
public class ServiceKeyVerifier {

    /** Cabecera de servicio compartida por las dos direcciones del contrato. */
    public static final String HEADER = "X-Internal-Api-Key";

    private final byte[] expected;

    public ServiceKeyVerifier(String configuredKey) {
        this.expected = configuredKey == null ? new byte[0] : configuredKey.getBytes(StandardCharsets.UTF_8);
    }

    /** @return {@code true} si el servicio tiene configurada una clave de entrada */
    public boolean isConfigured() {
        return this.expected.length > 0;
    }

    /**
     * @param providedKey valor recibido en la cabecera (puede ser {@code null})
     * @return {@code true} solo si coincide con la clave configurada
     */
    public boolean isValid(String providedKey) {
        if (!isConfigured() || providedKey == null) {
            return false;
        }
        return MessageDigest.isEqual(providedKey.getBytes(StandardCharsets.UTF_8), this.expected);
    }
}
