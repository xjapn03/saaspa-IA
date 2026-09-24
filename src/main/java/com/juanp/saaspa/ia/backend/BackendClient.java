package com.juanp.saaspa.ia.backend;

import java.net.URI;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/**
 * Cliente HTTP delgado hacia saaspa-backend (API interna {@code /api/internal/v1/*}).
 *
 * <p>Responsabilidades y limites (AGENTS.md, reglas R1, R2 y R4):
 * <ul>
 *   <li>reenvia el turn token tal cual en {@code Authorization: Bearer} (ADR 0006);
 *   <li>anade la clave de servicio IA -&gt; NestJS en {@code X-Internal-Api-Key};
 *   <li>mapea los fallos a {@link BackendException} / {@link BackendUnavailableException};
 *   <li><strong>no</strong> decide negocio, no conoce el esquema {@code ia} y no interpreta los
 *       datos mas alla de deserializarlos.
 * </ul>
 *
 * <p>El {@code path} debe venir ya construido y sin PII en la query (el path se usa en mensajes de
 * error y logs): codifica los segmentos variables con
 * {@code UriUtils.encodePathSegment(valor, StandardCharsets.UTF_8)}.
 */
public class BackendClient {

	private static final Logger log = LoggerFactory.getLogger(BackendClient.class);

	private final RestClient restClient;

	BackendClient(RestClient restClient) {
		this.restClient = restClient;
	}

	/**
	 * GET sin parametros.
	 *
	 * @param path ruta relativa a la URL base, por ejemplo {@code /api/internal/v1/services}
	 * @param turnToken turn token que se reenvia; puede ser {@code null} si no hay identidad
	 * @param responseType tipo esperado de la respuesta
	 */
	public <T> T get(String path, String turnToken, Class<T> responseType) {
		return get(path, Map.of(), turnToken, responseType);
	}

	/**
	 * GET con parametros de consulta opcionales.
	 *
	 * @param path ruta relativa a la URL base (sin query)
	 * @param queryParams parametros de consulta; los valores {@code null} se omiten
	 * @param turnToken turn token que se reenvia; puede ser {@code null} si no hay identidad
	 * @param responseType tipo esperado de la respuesta
	 * @throws BackendException si el backend responde con error o con un cuerpo vacio
	 * @throws BackendUnavailableException si no hay respuesta (timeout, conexion rechazada)
	 */
	public <T> T get(String path, Map<String, ?> queryParams, String turnToken, Class<T> responseType) {
		try {
			T body = this.restClient.get()
					.uri(uriBuilder -> buildUri(uriBuilder, path, queryParams))
					.headers(headers -> {
						if (StringUtils.hasText(turnToken)) {
							headers.setBearerAuth(turnToken);
						}
					})
					.retrieve()
					.body(responseType);
			if (body == null) {
				throw BackendException.emptyBody(path);
			}
			return body;
		}
		catch (RestClientResponseException ex) {
			log.debug("Backend {} respondio HTTP {}", path, ex.getStatusCode().value());
			throw BackendException.fromStatus(ex.getStatusCode().value(), path, ex.getResponseBodyAsString());
		}
		catch (ResourceAccessException ex) {
			log.warn("Backend {} sin respuesta: {}", path, ex.getClass().getSimpleName());
			throw new BackendUnavailableException(path, ex);
		}
		catch (RestClientException ex) {
			log.warn("Backend {} fallo inesperado: {}", path, ex.getClass().getSimpleName());
			throw BackendException.unexpected(path, ex.getMessage(), ex);
		}
	}

	private static URI buildUri(UriBuilder uriBuilder, String path, Map<String, ?> queryParams) {
		UriBuilder builder = uriBuilder.path(path);
		queryParams.forEach((name, value) -> {
			if (value != null) {
				builder.queryParam(name, value);
			}
		});
		return builder.build();
	}
}
