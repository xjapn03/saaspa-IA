package com.juanp.saaspa.ia.usage;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.util.StringUtils;

/**
 * Convierte la clave de origen de un turno (ADR 0020) en un pseudonimo almacenable en {@code ia.turn_log}.
 *
 * <p>El tope por origen necesita contar turnos del mismo origen sin guardar la IP: se guarda un
 * <strong>HMAC-SHA256 con sal</strong> (hex), determinista en la ventana del tope. Nunca se registra el
 * origen ni la IP en claro, tampoco en logs (R8). Es pseudonimizacion, no anonimizacion: el hash solo es
 * dificil de revertir si la sal es secreta.
 *
 * <p>La sal sale de {@code IA_COST_GUARD_ORIGIN_SALT} y la politica es de <strong>fallo cerrado</strong>
 * (HN-01): fuera del perfil {@code local} el arranque falla si falta, esta en blanco, mide menos de
 * {@value #MIN_SALT_LENGTH} caracteres o es el valor legado "saaspa-ia-origin-sin-sal" —la constante
 * publica que antes servia de fallback y con la que una IP se revertia por fuerza bruta desde la tabla—.
 * En {@code local} sin sal se genera una aleatoria por proceso: el desarrollo no necesita secretos y nadie
 * mas queda expuesto.
 */
public class OriginHasher {

	private static final String ALGORITHM = "HmacSHA256";

	/** Longitud minima de la sal configurada fuera del perfil local. */
	static final int MIN_SALT_LENGTH = 16;

	/**
	 * Valor de la sal del fallback eliminado en HN-01: era una constante publica del repo, asi que el hash
	 * calculado con ella era reversible por fuerza bruta. Se veta de forma explicita para que nadie lo
	 * configure. Es privado a proposito: no es una clave, es un valor rechazado.
	 */
	private static final String REJECTED_LEGACY_VALUE = "saaspa-ia-origin-sin-sal";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final byte[] salt;

	/**
	 * @param salt sal ya elegida; nunca en blanco
	 * @throws IllegalArgumentException si la sal esta en blanco
	 */
	public OriginHasher(String salt) {
		if (!StringUtils.hasText(salt)) {
			throw new IllegalArgumentException("La sal del hash de origen no puede estar en blanco");
		}
		this.salt = salt.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Fabrica que aplica la politica de HN-01 segun el perfil activo.
	 *
	 * @param configuredSalt valor de {@code IA_COST_GUARD_ORIGIN_SALT} tal cual llega del entorno
	 * @param localProfile si el perfil {@code local} esta activo
	 * @return hasher listo; en {@code local} sin sal, con una sal aleatoria de proceso
	 * @throws IllegalStateException fuera de {@code local} si la sal falta, esta en blanco, mide menos de
	 *             {@value #MIN_SALT_LENGTH} caracteres o es el valor legado del repo
	 */
	public static OriginHasher create(String configuredSalt, boolean localProfile) {
		if (localProfile && !StringUtils.hasText(configuredSalt)) {
			return new OriginHasher(randomSalt());
		}
		if (!StringUtils.hasText(configuredSalt)) {
			throw new IllegalStateException("Falta la sal del hash de origen (IA_COST_GUARD_ORIGIN_SALT): es "
					+ "obligatoria fuera del perfil local (HN-01, ADR 0020). Genera: openssl rand -hex 32");
		}
		if (configuredSalt.length() < MIN_SALT_LENGTH) {
			throw new IllegalStateException("La sal del hash de origen (IA_COST_GUARD_ORIGIN_SALT) mide menos de "
					+ MIN_SALT_LENGTH + " caracteres (HN-01, ADR 0020)");
		}
		if (REJECTED_LEGACY_VALUE.equals(configuredSalt)) {
			throw new IllegalStateException("La sal del hash de origen (IA_COST_GUARD_ORIGIN_SALT) es la constante "
					+ "legada y publica del repo: con ella el hash de una IP es reversible. Genera un valor propio "
					+ "(HN-01, ADR 0020)");
		}
		return new OriginHasher(configuredSalt);
	}

	/**
	 * @param origin clave de origen del turno (puede ser {@code null})
	 * @return hash hexadecimal del origen, o {@code null} si el turno no trae origen
	 */
	public String hash(String origin) {
		if (origin == null) {
			return null;
		}
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(this.salt, ALGORITHM));
			return HexFormat.of().formatHex(mac.doFinal(origin.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			// HmacSHA256 esta en toda JVM y la clave siempre es no vacia: esto no deberia ocurrir.
			throw new IllegalStateException("No se pudo calcular el hash de origen", ex);
		}
	}

	private static String randomSalt() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}
}
