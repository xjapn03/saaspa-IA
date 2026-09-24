package com.juanp.saaspa.ia.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracion de la verificacion del turn token (ADR 0006).
 *
 * <p>Este servicio <strong>solo verifica</strong> con claves publicas: NestJS firma el turn token
 * con ES256 (P-256) y anade el {@code kid} en la cabecera del JWT. Se admiten varias claves a la vez
 * para poder rotar sin cortar el servicio (por ejemplo {@code current} firmando y {@code previous}
 * todavia aceptada).
 *
 * <p>El material de cada clave se entrega en base64 (recomendado, una linea) o como PEM en crudo
 * (multilinea con {@code -----BEGIN PUBLIC KEY-----}).
 *
 * @param audience valor esperado del claim {@code aud}
 * @param issuer valor esperado del claim {@code iss}; vacio para no validarlo
 * @param keys claves publicas aceptadas, cada una con su {@code kid}
 */
@ConfigurationProperties("saaspa.turn-token")
public record TurnTokenProperties(
		@DefaultValue("saaspa-ia") String audience,
		@DefaultValue("") String issuer,
		@DefaultValue List<TurnTokenKey> keys) {

	/**
	 * Clave publica aceptada para verificar turn tokens.
	 *
	 * @param kid identificador de la clave (cabecera {@code kid} del JWT)
	 * @param publicKey clave publica EC en base64 o PEM
	 */
	public record TurnTokenKey(@DefaultValue("") String kid, @DefaultValue("") String publicKey) {

		/**
		 * @return {@code true} si la entrada no aporta nada y debe ignorarse
		 */
		public boolean isEmpty() {
			return this.kid.isBlank() || this.publicKey.isBlank();
		}
	}
}
