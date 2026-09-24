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
 * error y logs). Para rutas con segmentos variables usa la sobrecarga con plantilla
 * ({@code /services/{servicio}}): el cliente expande y codifica los valores.
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
	 * @param path ruta relativa a la URL base, sin variables ni caracteres por codificar
	 * @param queryParams parametros de consulta; los valores {@code null} se omiten
	 * @param turnToken turn token que se reenvia; puede ser {@code null} si no hay identidad
	 * @param responseType tipo esperado de la respuesta
	 * @throws BackendException si el backend responde con error o con un cuerpo vacio
	 * @throws BackendUnavailableException si no hay respuesta (timeout, conexion rechazada)
	 */
	public <T> T get(String path, Map<String, ?> queryParams, String turnToken, Class<T> responseType) {
		return get(path, Map.of(), queryParams, turnToken, responseType);
	}

	/**
	 * GET con variables de ruta y parametros de consulta.
	 *
	 * <p>El {@code pathTemplate} admite variables tipo {@code /services/{servicio}}; este cliente las
	 * expande y las codifica, asi que <strong>no</strong> hay que codificarlas antes (codificar a mano
	 * produciria doble codificacion).
	 *
	 * @param pathTemplate ruta relativa a la URL base, con variables entre llaves si las hay
	 * @param uriVariables valores de las variables de la plantilla
	 * @param queryParams parametros de consulta; los valores {@code null} se omiten
	 * @param turnToken turn token que se reenvia; puede ser {@code null} si no hay identidad
	 * @param responseType tipo esperado de la respuesta
	 * @throws BackendException si el backend responde con error o con un cuerpo vacio
	 * @throws BackendUnavailableException si no hay respuesta (timeout, conexion rechazada)
	 */
	public <T> T get(String pathTemplate, Map<String, ?> uriVariables, Map<String, ?> queryParams, String turnToken,
			Class<T> responseType) {
		try {
			T body = this.restClient.get()
					.uri(uriBuilder -> buildUri(uriBuilder, pathTemplate, uriVariables, queryParams))
					.headers(headers -> {
						if (StringUtils.hasText(turnToken)) {
							headers.setBearerAuth(turnToken);
						}
					})
					.retrieve()
					.body(responseType);
			if (body == null) {
				throw BackendException.emptyBody(pathTemplate);
			}
			return body;
		}
		catch (RestClientResponseException ex) {
			log.debug("Backend {} respondio HTTP {}", pathTemplate, ex.getStatusCode().value());
			throw BackendException.fromStatus(ex.getStatusCode().value(), pathTemplate, ex.getResponseBodyAsString());
		}
		catch (ResourceAccessException ex) {
			log.warn("Backend {} sin respuesta: {}", pathTemplate, ex.getClass().getSimpleName());
			throw new BackendUnavailableException(pathTemplate, ex);
		}
		catch (RestClientException ex) {
			log.warn("Backend {} fallo inesperado: {}", pathTemplate, ex.getClass().getSimpleName());
			throw BackendException.unexpected(pathTemplate, ex.getMessage(), ex);
		}
	}

	private static URI buildUri(UriBuilder uriBuilder, String pathTemplate, Map<String, ?> uriVariables,
			Map<String, ?> queryParams) {
		UriBuilder builder = uriBuilder.path(pathTemplate);
		queryParams.forEach((name, value) -> {
			if (value != null) {
				builder.queryParam(name, value);
			}
		});
		return builder.build(uriVariables);
	}
}
