package com.juanp.saaspa.ia.backend;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Contrato del cliente HTTP hacia NestJS (tarea T1.2): ruta y parametros de consulta, cabeceras de
 * servicio y de turn token, deserializacion y mapeo de errores. No se llama a ningun backend real.
 */
class BackendClientWireMockTest {

	private static final String SERVICE_KEY = "test-internal-key";

	private static final String SERVICES_PATH = "/api/internal/v1/services";

	private static final String PAGE_JSON = """
			{"data":[{"id":"srv-1","name":"Masaje relajante","price":120000.0}],
			 "total":1,"page":1,"limit":20,"totalPages":1}
			""";

	private static WireMockServer backend;

	@BeforeAll
	static void startBackend() {
		backend = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		backend.start();
	}

	@AfterAll
	static void stopBackend() {
		backend.stop();
	}

	@BeforeEach
	void resetStubs() {
		backend.resetAll();
	}

	@Test
	@DisplayName("envia la clave de servicio, reenvia el turn token y deserializa la respuesta")
	void sendsServiceKeyAndTurnToken() {
		backend.stubFor(get(urlPathEqualTo(SERVICES_PATH)).willReturn(okJson(PAGE_JSON)));

		withBackend(backend.baseUrl(), Duration.ofSeconds(2)).run(context -> {
			assertThat(context).hasNotFailed();

			Page page = context.getBean(BackendClient.class)
					.get(SERVICES_PATH, Map.of("page", 1, "limit", 20), "turn-token-123", Page.class);

			assertThat(page.total()).isEqualTo(1);
			assertThat(page.data()).singleElement().satisfies(item -> {
				assertThat(item.id()).isEqualTo("srv-1");
				assertThat(item.name()).isEqualTo("Masaje relajante");
				assertThat(item.price()).isEqualTo(120000.0);
			});

			backend.verify(getRequestedFor(urlPathEqualTo(SERVICES_PATH))
					.withHeader(BackendClientConfig.SERVICE_KEY_HEADER, equalTo(SERVICE_KEY))
					.withHeader("Authorization", equalTo("Bearer turn-token-123"))
					.withQueryParam("page", equalTo("1"))
					.withQueryParam("limit", equalTo("20")));
		});
	}

	@Test
	@DisplayName("expande y codifica las variables de ruta sin doble codificacion")
	void expandsPathVariables() {
		String encodedPath = SERVICES_PATH + "/Masaje%20relajante%20%C3%B1";
		backend.stubFor(get(urlPathEqualTo(encodedPath)).willReturn(okJson(PAGE_JSON)));

		withBackend(backend.baseUrl(), Duration.ofSeconds(2)).run(context -> {
			context.getBean(BackendClient.class).get(SERVICES_PATH + "/{servicio}",
					Map.of("servicio", "Masaje relajante ñ"), Map.of(), "turn-token-123", Page.class);

			backend.verify(getRequestedFor(urlPathEqualTo(encodedPath)));
		});
	}

	@Test
	@DisplayName("sin turn token no se envia la cabecera Authorization")
	void omitsAuthorizationWithoutTurnToken() {
		backend.stubFor(get(urlPathEqualTo(SERVICES_PATH)).willReturn(okJson(PAGE_JSON)));

		withBackend(backend.baseUrl(), Duration.ofSeconds(2)).run(context -> {
			context.getBean(BackendClient.class).get(SERVICES_PATH, null, Page.class);

			backend.verify(getRequestedFor(urlPathEqualTo(SERVICES_PATH)).withoutHeader("Authorization"));
		});
	}

	@Test
	@DisplayName("sin clave de servicio configurada no se envia X-Internal-Api-Key")
	void omitsServiceKeyWhenBlank() {
		backend.stubFor(get(urlPathEqualTo(SERVICES_PATH)).willReturn(okJson(PAGE_JSON)));

		withBackend(backend.baseUrl(), Duration.ofSeconds(2), "").run(context -> {
			context.getBean(BackendClient.class).get(SERVICES_PATH, null, Page.class);

			backend.verify(getRequestedFor(urlPathEqualTo(SERVICES_PATH))
					.withoutHeader(BackendClientConfig.SERVICE_KEY_HEADER));
		});
	}

	@Test
	@DisplayName("error HTTP del backend: BackendException con estado y cuerpo crudo")
	void mapsHttpErrorToBackendException() {
		String errorBody = "{\"statusCode\":404,\"message\":\"Servicio no encontrado\",\"error\":\"Not Found\"}";
		backend.stubFor(get(urlPathEqualTo(SERVICES_PATH)).willReturn(aResponse()
				.withStatus(404)
				.withHeader("Content-Type", "application/json")
				.withBody(errorBody)));

		withBackend(backend.baseUrl(), Duration.ofSeconds(2)).run(context -> {
			BackendClient client = context.getBean(BackendClient.class);

			assertThatThrownBy(() -> client.get(SERVICES_PATH, null, Page.class))
					.isInstanceOf(BackendException.class)
					.isNotInstanceOf(BackendUnavailableException.class)
					.hasMessageContaining("HTTP 404")
					.hasMessageContaining(SERVICES_PATH)
					.satisfies(error -> {
						BackendException backendError = (BackendException) error;
						assertThat(backendError.status()).contains(404);
						assertThat(backendError.responseBody())
								.hasValueSatisfying(body -> assertThat(body).contains("Servicio no encontrado"));
					});
		});
	}

	@Test
	@DisplayName("respuesta vacia: BackendException sin estado")
	void mapsEmptyBodyToBackendException() {
		backend.stubFor(get(urlPathEqualTo(SERVICES_PATH)).willReturn(ok()));

		withBackend(backend.baseUrl(), Duration.ofSeconds(2)).run(context -> {
			BackendClient client = context.getBean(BackendClient.class);

			assertThatThrownBy(() -> client.get(SERVICES_PATH, null, Page.class))
					.isInstanceOf(BackendException.class)
					.isNotInstanceOf(BackendUnavailableException.class)
					.hasMessageContaining("vacia");
		});
	}

	@Test
	@DisplayName("read timeout: BackendUnavailableException")
	void mapsReadTimeoutToBackendUnavailable() {
		backend.stubFor(get(urlPathEqualTo(SERVICES_PATH)).willReturn(okJson(PAGE_JSON).withFixedDelay(2000)));

		withBackend(backend.baseUrl(), Duration.ofMillis(300)).run(context -> {
			BackendClient client = context.getBean(BackendClient.class);

			assertThatThrownBy(() -> client.get(SERVICES_PATH, null, Page.class))
					.isInstanceOf(BackendUnavailableException.class)
					.hasMessageContaining(SERVICES_PATH);
		});
	}

	@Test
	@DisplayName("backend caido: BackendUnavailableException")
	void mapsConnectionRefusedToBackendUnavailable() {
		String closedPortUrl = freeClosedPortUrl();

		withBackend(closedPortUrl, Duration.ofSeconds(1)).run(context -> {
			BackendClient client = context.getBean(BackendClient.class);

			assertThatThrownBy(() -> client.get(SERVICES_PATH, null, Page.class))
					.isInstanceOf(BackendUnavailableException.class)
					.hasMessageContaining(SERVICES_PATH);
		});
	}

	private static String freeClosedPortUrl() {
		WireMockServer temporary = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		temporary.start();
		String url = temporary.baseUrl();
		temporary.stop();
		return url;
	}

	private ApplicationContextRunner withBackend(String baseUrl, Duration readTimeout) {
		return withBackend(baseUrl, readTimeout, SERVICE_KEY);
	}

	private ApplicationContextRunner withBackend(String baseUrl, Duration readTimeout, String serviceKey) {
		return new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
				.withUserConfiguration(BackendClientConfig.class)
				.withPropertyValues(
						"saaspa.backend.base-url=" + baseUrl,
						"saaspa.backend.internal-api-key=" + serviceKey,
						"saaspa.backend.connect-timeout=500ms",
						"saaspa.backend.read-timeout=" + readTimeout.toMillis() + "ms");
	}

	record Page(List<Item> data, int total, int page, int limit, int totalPages) {
	}

	record Item(String id, String name, double price) {
	}

}
