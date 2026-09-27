package com.juanp.saaspa.ia.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Topes de coste por tenant y por conversacion (ADR 0010).
 *
 * <p>Los valores son configurables por entorno y los de por defecto son **conservadores** y estan
 * justificados en {@code application.yml}: el objetivo es cortar un bucle descontrolado o un abuso sin
 * cortar el uso legitimo del piloto. La ventana se alinea con la del backend (30 mensajes por hora y por
 * sesion anonima) para no cortar antes que el gateway.
 *
 * <p>El consumo se mide siempre contra {@code ia.turn_log} (fuente de verdad), nunca con un contador en
 * memoria.
 *
 * @param enabled si el guardia esta activo (permite apagarlo sin desplegar codigo nuevo)
 * @param window ventana movil de los topes (por defecto una hora)
 * @param tenantMaxTurns turnos maximos por tenant en la ventana
 * @param tenantMaxTokens tokens maximos (entrada + salida) por tenant en la ventana
 * @param conversationMaxTurns turnos maximos por conversacion en la ventana
 * @param conversationMaxTokens tokens maximos (entrada + salida) por conversacion en la ventana
 */
@ConfigurationProperties("saaspa.cost-guard")
public record CostGuardProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("1h") Duration window,
		@DefaultValue("240") int tenantMaxTurns,
		@DefaultValue("1000000") long tenantMaxTokens,
		@DefaultValue("30") int conversationMaxTurns,
		@DefaultValue("150000") long conversationMaxTokens) {
}
