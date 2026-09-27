package com.juanp.saaspa.ia.usage;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Convierte la clave de origen de un turno (ADR 0020) en un pseudonimo almacenable en {@code ia.turn_log}.
 *
 * <p>El tope por origen necesita contar turnos del mismo origen sin guardar la IP: se guarda un
 * <strong>HMAC-SHA256 con sal</strong> (hex), que es determinista en la ventana del tope y no se puede
 * revertir sin el secreto. Nunca se registra el origen ni la IP en claro, tampoco en logs (R8).
 *
 * <p>La sal sale de {@code IA_COST_GUARD_ORIGIN_SALT}. Si falta, el hash se calcula igual (con una clave
 * constante y conocida) y se avisa al arrancar: <strong>el tope sigue funcionando</strong>, lo que se debilita
 * es la proteccion de la IP en reposo (un hash sin secreto se revierte por fuerza bruta en segundos), asi
 * que en produccion la sal es obligatoria.
 */
public class OriginHasher {

	private static final Logger log = LoggerFactory.getLogger(OriginHasher.class);

	private static final String ALGORITHM = "HmacSHA256";

	/** Clave constante cuando no hay sal configurada: {@link SecretKeySpec} no acepta una clave vacia. */
	private static final byte[] NO_SALT = "saaspa-ia-origin-sin-sal".getBytes(StandardCharsets.UTF_8);

	private final byte[] salt;

	public OriginHasher(String salt) {
		this.salt = (salt == null || salt.isBlank()) ? NO_SALT : salt.getBytes(StandardCharsets.UTF_8);
		if (this.salt == NO_SALT) {
			log.warn("No hay sal para el hash de origen (IA_COST_GUARD_ORIGIN_SALT): el tope por origen funciona, "
					+ "pero el origen queda hasheado sin secreto y una IP se podria revertir desde la tabla");
		}
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
}
