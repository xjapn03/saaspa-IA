package com.juanp.saaspa.ia.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.StringUtils;

import com.juanp.saaspa.ia.config.TurnTokenProperties;

/**
 * Construye el verificador de turn tokens (ADR 0006): ES256 obligatorio y claves publicas EC
 * seleccionadas por {@code kid}.
 *
 * <p>Se usa el resource server de Spring Security (Nimbus por debajo).
 * {@code NimbusJwtDecoder.withPublicKey(...)} solo admite RSA, por lo que la via soportada para EC
 * es {@code withJwkSource(...)} con un {@link JWKSet} construido aqui (comprobado contra
 * spring-security-oauth2-jose 7.1.1). Restringir el algoritmo a ES256 evita ataques de confusion de
 * algoritmo (por ejemplo un token firmado con HS256 usando la clave publica como secreto).
 *
 * <p>Si no hay ninguna clave configurada el decoder se construye igual con un conjunto vacio: toda
 * peticion se rechaza (fallo cerrado) y se registra un aviso al arrancar.
 */
public final class TurnTokenDecoderFactory {

    private static final Logger log = LoggerFactory.getLogger(TurnTokenDecoderFactory.class);

    static final String AUDIENCE_CLAIM = "aud";

    static final String ISSUER_CLAIM = "iss";

    private static final String PEM_MARKER = "-----BEGIN";

    private TurnTokenDecoderFactory() {
    }

    /**
     * @param properties configuracion de claves, audiencia e issuer
     * @return verificador de turn tokens listo para el resource server
     */
    public static JwtDecoder create(TurnTokenProperties properties) {
        List<JWK> jwks = parseKeys(properties.keys());
        if (jwks.isEmpty()) {
            log.warn("No hay claves publicas de turn token configuradas: se rechazaran todas las peticiones de chat (401)");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSource(new ImmutableJWKSet<SecurityContext>(new JWKSet(jwks)))
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        decoder.setJwtValidator(createValidator(properties));
        return decoder;
    }

    private static List<JWK> parseKeys(List<TurnTokenProperties.TurnTokenKey> keys) {
        List<JWK> jwks = new ArrayList<>();
        for (TurnTokenProperties.TurnTokenKey key : keys) {
            if (!key.isEmpty()) {
                jwks.add(toEcJwk(key));
            }
        }
        return jwks;
    }

    private static JWK toEcJwk(TurnTokenProperties.TurnTokenKey key) {
        return new ECKey.Builder(Curve.P_256, parseEcPublicKey(key)).keyID(key.kid()).build();
    }

    private static ECPublicKey parseEcPublicKey(TurnTokenProperties.TurnTokenKey key) {
        try {
            byte[] der = toDer(key.publicKey());
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        }
        catch (NoSuchAlgorithmException | InvalidKeySpecException | ClassCastException | IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Clave publica del turn token invalida para el kid " + key.kid() + ": " + ex.getClass().getSimpleName(), ex);
        }
    }

    /**
     * Acepta el material en base64 (una linea, comodo para variables de entorno) o en PEM en crudo.
     * En el caso base64 puede contener el PEM completo o directamente el DER.
     */
    private static byte[] toDer(String material) {
        String trimmed = material.trim();
        if (trimmed.contains(PEM_MARKER)) {
            return Base64.getMimeDecoder().decode(withoutPemHeaders(trimmed));
        }
        byte[] decoded = Base64.getMimeDecoder().decode(trimmed);
        return isPem(decoded) ? Base64.getMimeDecoder().decode(withoutPemHeaders(new String(decoded, StandardCharsets.US_ASCII)))
                : decoded;
    }

    private static boolean isPem(byte[] material) {
        byte[] marker = PEM_MARKER.getBytes(StandardCharsets.US_ASCII);
        if (material.length < marker.length) {
            return false;
        }
        for (int i = 0; i < marker.length; i++) {
            if (material[i] != marker[i]) {
                return false;
            }
        }
        return true;
    }

    private static String withoutPemHeaders(String pem) {
        return pem.replaceAll("-----[A-Z ]+-----", "");
    }

    private static OAuth2TokenValidator<Jwt> createValidator(TurnTokenProperties properties) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefault());
        validators.add(new JwtClaimValidator<List<String>>(AUDIENCE_CLAIM,
                audiences -> audiences != null && audiences.contains(properties.audience())));
        if (StringUtils.hasText(properties.issuer())) {
            validators.add(new JwtClaimValidator<String>(ISSUER_CLAIM, properties.issuer()::equals));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }
}
