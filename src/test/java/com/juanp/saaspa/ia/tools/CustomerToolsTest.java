package com.juanp.saaspa.ia.tools;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.context.SecurityContextHolder;

import com.juanp.saaspa.ia.backend.BackendClient;
import com.juanp.saaspa.ia.backend.BackendClientConfig;
import com.juanp.saaspa.ia.config.TenantProperties;
import com.juanp.saaspa.ia.security.TurnToken;
import com.juanp.saaspa.ia.security.TurnTokenAuthentication;

/**
 * Contrato de las herramientas de lectura (T1.3) con WireMock: rutas, cabeceras, mapeo de datos y
 * respuestas de fallo. No se llama a ningun backend real ni a ningun LLM.
 */
class CustomerToolsTest {

	private static final String SERVICE_KEY = "test-internal-key";

	private static final String TURN_TOKEN = "turn-token-123";

	private static final String SERVICES_PATH = "/api/internal/v1/services";

	private static final String AVAILABILITY_PATH = "/api/internal/v1/availability";

	/** Reloj fijo: en Bogota es el 1 de octubre de 2026. */
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"),
			ZoneId.of("America/Bogota"));

	private static final String SERVICE_JSON = """
			{"data":[{"id":"srv-1","name":"Masaje relajante","slug":"masaje-relajante",
			  "description":"Masaje de 60 minutos","price":120000.0,"compareAtPrice":150000.0,"duration":60,
			  "isActive":true,"isFeatured":true,
			  "category":{"id":"cat-1","name":"Masajes","slug":"masajes"}}],
			 "total":1,"page":1,"limit":50,"totalPages":1}
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
	void setUp() {
		backend.resetAll();
		authenticate();
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("listarServicios: consulta el catalogo, formatea precios y reenvia el turn token")
	void listarServicios() {
		backend.stubFor(get(urlPathEqualTo(SERVICES_PATH)).willReturn(okJson(SERVICE_JSON)));

		withBackend(backend.baseUrl()).run(context -> {
			assertThat(context).hasSingleBean(CustomerTools.class);

			CustomerTools.ServiceListResult result = tools(context).listarServicios(true);

			assertThat(result.ok()).isTrue();
			assertThat(result.total()).isEqualTo(1);
			assertThat(result.truncated()).isFalse();
			assertThat(result.services()).singleElement().satisfies(service -> {
				assertThat(service.id()).isEqualTo("srv-1");
				assertThat(service.slug()).isEqualTo("masaje-relajante");
				assertThat(service.name()).isEqualTo("Masaje relajante");
				assertThat(service.durationMinutes()).isEqualTo(60);
				assertThat(service.priceCop()).isEqualTo("$ 120.000");
				assertThat(service.compareAtPriceCop()).isEqualTo("$ 150.000");
				assertThat(service.category()).isEqualTo("Masajes");
			});

			backend.verify(getRequestedFor(urlPathEqualTo(SERVICES_PATH))
					.withHeader("X-Internal-Api-Key", equalTo(SERVICE_KEY))
					.withHeader("Authorization", equalTo("Bearer " + TURN_TOKEN))
					.withQueryParam("page", equalTo("1"))
					.withQueryParam("limit", equalTo("50"))
					.withQueryParam("featured", equalTo("true")));
		});
	}

	@Test
	@DisplayName("consultarServicio: codifica el slug en la ruta y devuelve la descripcion")
	void consultarServicioEncodesSlug() {
		String encodedPath = "/api/internal/v1/services/Masaje%20relajante%20%C3%B1";
		backend.stubFor(get(urlPathEqualTo(encodedPath)).willReturn(okJson("""
				{"id":"srv-9","name":"Masaje con ñ","slug":"masaje-relajante-ñ","description":"Incluye aromaterapia",
				 "price":90000.0,"duration":45,"isActive":true,"isFeatured":false}
				""")));

		withBackend(backend.baseUrl()).run(context -> {
			CustomerTools.ServiceDetailResult result = tools(context).consultarServicio("Masaje relajante ñ");

			assertThat(result.ok()).isTrue();
			assertThat(result.description()).isEqualTo("Incluye aromaterapia");
			assertThat(result.service().priceCop()).isEqualTo("$ 90.000");
			backend.verify(getRequestedFor(urlPathEqualTo(encodedPath))
					.withHeader("Authorization", equalTo("Bearer " + TURN_TOKEN)));
		});
	}

	@Test
	@DisplayName("consultarDisponibilidad: mapea las franjas con hora local de la sede")
	void consultarDisponibilidadMapsLocalTime() {
		backend.stubFor(get(urlPathEqualTo(AVAILABILITY_PATH)).willReturn(okJson("""
				{"serviceId":"srv-1","date":"2026-10-02","timezone":"America/Bogota",
				 "slots":[{"start":"2026-10-02T13:00:00Z","end":"2026-10-02T14:00:00Z"},
				          {"start":"2026-10-02T14:00:00Z","end":"2026-10-02T15:00:00Z"}]}
				""")));

		withBackend(backend.baseUrl()).run(context -> {
			CustomerTools.AvailabilityResult result = tools(context).consultarDisponibilidad("srv-1", "2026-10-02");

			assertThat(result.ok()).isTrue();
			assertThat(result.message()).isNull();
			assertThat(result.date()).isEqualTo("2026-10-02");
			assertThat(result.timezone()).isEqualTo("America/Bogota");
			assertThat(result.slots()).extracting(CustomerTools.TimeSlot::localStartTime)
					.containsExactly("08:00", "09:00");

			backend.verify(getRequestedFor(urlPathEqualTo(AVAILABILITY_PATH))
					.withQueryParam("serviceId", equalTo("srv-1"))
					.withQueryParam("date", equalTo("2026-10-02")));
		});
	}

	@Test
	@DisplayName("consultarDisponibilidad: sin franjas devuelve ok con aviso, no inventa horarios")
	void consultarDisponibilidadWithoutSlots() {
		backend.stubFor(get(urlPathEqualTo(AVAILABILITY_PATH)).willReturn(okJson("""
				{"serviceId":"srv-1","date":"2026-10-03","timezone":"America/Bogota","slots":[]}
				""")));

		withBackend(backend.baseUrl()).run(context -> {
			CustomerTools.AvailabilityResult result = tools(context).consultarDisponibilidad("srv-1", "2026-10-03");

			assertThat(result.ok()).isTrue();
			assertThat(result.slots()).isEmpty();
			assertThat(result.message()).isEqualTo("No hay franjas disponibles ese dia");
		});
	}

	@Test
	@DisplayName("consultarDisponibilidad: fecha mal formada o pasada no llega al backend")
	void consultarDisponibilidadValidatesDate() {
		withBackend(backend.baseUrl()).run(context -> {
			CustomerTools.AvailabilityResult badFormat = tools(context).consultarDisponibilidad("srv-1", "manana");
			CustomerTools.AvailabilityResult past = tools(context).consultarDisponibilidad("srv-1", "2026-09-30");

			assertThat(badFormat.ok()).isFalse();
			assertThat(badFormat.message()).contains("AAAA-MM-DD").contains("2026-10-01");
			assertThat(past.ok()).isFalse();
			assertThat(past.message()).contains("ya paso");
			backend.verify(0, getRequestedFor(urlPathEqualTo(AVAILABILITY_PATH)));
		});
	}

	@Test
	@DisplayName("un error del backend se convierte en ok=false con mensaje, sin excepcion")
	void mapsBackendError() {
		backend.stubFor(get(urlPathEqualTo(SERVICES_PATH)).willReturn(aResponse().withStatus(500)));
		backend.stubFor(get(urlPathEqualTo("/api/internal/v1/services/srv-404"))
				.willReturn(aResponse().withStatus(404)));

		withBackend(backend.baseUrl()).run(context -> {
			CustomerTools.ServiceListResult catalogue = tools(context).listarServicios(null);
			CustomerTools.ServiceDetailResult missing = tools(context).consultarServicio("srv-404");

			assertThat(catalogue.ok()).isFalse();
			assertThat(catalogue.message()).isEqualTo("El sistema de agenda no pudo responder la consulta");
			assertThat(missing.ok()).isFalse();
			assertThat(missing.message()).contains("listarServicios");
		});
	}

	@Test
	@DisplayName("si el backend no responde, ok=false sin excepcion")
	void mapsUnreachableBackend() {
		withBackend(closedPortUrl()).run(context -> {
			CustomerTools.ServiceListResult result = tools(context).listarServicios(null);

			assertThat(result.ok()).isFalse();
			assertThat(result.message()).isEqualTo("El sistema de agenda no respondio en este momento");
			assertThat(result.services()).isEmpty();
		});
	}

	@Test
	@DisplayName("sin turn token verificado no se llama al backend")
	void withoutTurnToken() {
		SecurityContextHolder.clearContext();

		withBackend(backend.baseUrl()).run(context -> {
			CustomerTools.ServiceListResult result = tools(context).listarServicios(null);

			assertThat(result.ok()).isFalse();
			assertThat(result.message()).contains("No se pudo identificar el turno");
			backend.verify(0, getRequestedFor(urlPathEqualTo(SERVICES_PATH)));
		});
	}

	@Test
	@DisplayName("formatCop usa el formato de pesos colombianos")
	void formatsColombianPesos() {
		assertThat(CustomerTools.formatCop(120000.0)).isEqualTo("$ 120.000");
		assertThat(CustomerTools.formatCop(95000.4)).isEqualTo("$ 95.000");
		assertThat(CustomerTools.formatCop(null)).isNull();
	}

	private static CustomerTools tools(AssertableApplicationContext context) {
		return new CustomerTools(context.getBean(BackendClient.class), context.getBean(TenantProperties.class), CLOCK);
	}

	private static ApplicationContextRunner withBackend(String baseUrl) {
		return new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
				.withUserConfiguration(BackendClientConfig.class, ToolsConfig.class)
				.withPropertyValues("saaspa.backend.base-url=" + baseUrl,
						"saaspa.backend.internal-api-key=" + SERVICE_KEY, "saaspa.backend.connect-timeout=500ms",
						"saaspa.backend.read-timeout=2s", "saaspa.tenant.timezone=America/Bogota");
	}

	private static String closedPortUrl() {
		WireMockServer temporary = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		temporary.start();
		String url = temporary.baseUrl();
		temporary.stop();
		return url;
	}

	private static void authenticate() {
		TurnToken token = new TurnToken("turn-1", "kamerinos", "conv-1", TurnToken.Channel.WEB_WIDGET,
				TurnToken.Agent.CLIENTAS, null, null, Instant.now().plusSeconds(300), TURN_TOKEN);
		SecurityContextHolder.getContext().setAuthentication(new TurnTokenAuthentication(token));
	}

}
