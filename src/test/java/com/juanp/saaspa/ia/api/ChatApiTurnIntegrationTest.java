package com.juanp.saaspa.ia.api;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.juanp.saaspa.ia.TestcontainersConfiguration;
import com.juanp.saaspa.ia.security.TestTurnTokens;
import com.juanp.saaspa.ia.security.TurnToken;
import com.juanp.saaspa.ia.security.TurnTokenAuthentication;
import com.juanp.saaspa.ia.tools.CustomerTools;
import com.juanp.saaspa.ia.usage.LoggingToolCallback;
import com.juanp.saaspa.ia.usage.OriginHasher;
import com.juanp.saaspa.ia.usage.ToolCallLogger;

import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

/**
 * Turno contra el endpoint real de este servicio ({@code POST /api/v1/chat}) con Testcontainers
 * Postgres y WireMock sirviendo el catalogo (T1.9): recorre la cadena de seguridad (clave de servicio +
 * turn token), la validacion del contexto, el handoff en codigo y la persistencia en {@code ia}.
 * Ningun test llama a un LLM real (R14): el {@code ChatModel} es guionizado.
 */
@SpringBootTest(properties = "spring.ai.deepseek.api-key=test-key",
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, ChatApiTurnIntegrationTest.ScriptedModelConfig.class })
class ChatApiTurnIntegrationTest {

	private static final String SERVICE_KEY = "test-service-key";

	private static final String KID = "kid-current";

	/** IP del origen que el backend pondra en el claim {@code clientIp} (ADR 0020). */
	private static final String CLIENT_IP = "203.0.113.7";

	private static final String ORIGIN_SALT = "test-origin-salt";

	private static final String SERVICES_PATH = "/api/internal/v1/services";

	private static final UUID TURN_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

	private static final KeyPair SIGNING_KEY = TestTurnTokens.generateKeyPair();

	private static final String CATALOGUE_JSON = """
			{"data":[{"id":"srv-1","name":"Masaje relajante","slug":"masaje-relajante",
			  "description":"Masaje de 60 minutos","price":120000.0,"compareAtPrice":null,"duration":60,
			  "isActive":true,"isFeatured":true,
			  "category":{"id":"cat-1","name":"Masajes","slug":"masajes"}}],
			 "total":1,"page":1,"limit":50,"totalPages":1}
			""";

	private static final WireMockServer BACKEND = new WireMockServer(WireMockConfiguration.options().dynamicPort());

	static {
		BACKEND.start();
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("saaspa.chat-api.service-key", () -> SERVICE_KEY);
		registry.add("saaspa.turn-token.keys[0].kid", () -> KID);
		registry.add("saaspa.turn-token.keys[0].public-key", () -> TestTurnTokens.base64Pem(SIGNING_KEY.getPublic()));
		registry.add("saaspa.backend.base-url", BACKEND::baseUrl);
		registry.add("saaspa.backend.internal-api-key", () -> "test-key");
		registry.add("saaspa.tenant.default", () -> "kamerinos");
		registry.add("saaspa.cost-guard.origin-salt", () -> ORIGIN_SALT);
		// Tope por origen pequeno para poder verlo saltar con tres turnos (ADR 0020).
		registry.add("saaspa.cost-guard.origin-max-turns", () -> "2");
	}

	@Value("${local.server.port}")
	private int port;

	private final RestTemplate restTemplate = new RestTemplate();

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private CustomerTools customerTools;

	@Autowired
	private ToolCallLogger toolCallLogger;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		BACKEND.resetAll();
		this.jdbcTemplate.update("DELETE FROM ia.turn_log");
		this.jdbcTemplate.update("DELETE FROM ia.tool_call_log");
		this.jdbcTemplate.update("DELETE FROM ia.spring_ai_chat_memory");
		ScriptedChatModel.CALLS.set(0);
		SecurityContextHolder.getContext().setAuthentication(new TurnTokenAuthentication(turnToken()));
	}

	@AfterAll
	static void stopBackend() {
		SecurityContextHolder.clearContext();
		BACKEND.stop();
	}

	@Test
	@DisplayName("POST /api/v1/chat atiende el turno y lo persiste en ia")
	void runsTurnOverHttp() {
		ResponseEntity<String> response = post("¿Cuánto cuesta el masaje relajante?");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains(ScriptedChatModel.REPLY_TEXT).contains("\"requested\":false");
		assertThat(count("ia.turn_log")).isEqualTo(1);
		assertThat(this.jdbcTemplate.queryForObject("SELECT tenant_id FROM ia.turn_log", String.class))
				.isEqualTo("kamerinos");
		// ADR 0020: el turno anonimo guarda el hash de su origen (la IP del claim clientIp), nunca la IP.
		String originHash = this.jdbcTemplate.queryForObject("SELECT origin_hash FROM ia.turn_log", String.class);
		assertThat(originHash).isEqualTo(new OriginHasher(ORIGIN_SALT).hash("ip:" + CLIENT_IP))
				.doesNotContain(CLIENT_IP);
	}

	@Test
	@DisplayName("sin la clave de servicio responde 401 y no toca la base")
	void rejectsWithoutServiceKey() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(TestTurnTokens.signValid(SIGNING_KEY, KID, claims()));

		assertThatThrownBy(() -> this.restTemplate.postForEntity(url("/api/v1/chat"),
				new HttpEntity<>(body("hola"), headers), String.class))
				.isInstanceOf(HttpClientErrorException.Unauthorized.class);

		assertThat(count("ia.turn_log")).isZero();
	}

	@Test
	@DisplayName("un tema de salud responde en codigo por HTTP, sin llamar al modelo")
	void handoffOverHttpAnswersInCode() {
		ResponseEntity<String> response = post("Estoy embarazada, puedo hacerme el masaje?");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("profesional").contains("\"reason\":\"HEALTH_TOPIC\"");
		assertThat(ScriptedChatModel.CALLS.get()).isZero();

		// ADR 0013 punto 3 / ADR 0015: el turno derivado deja fila con su estado y su motivo (y sin
		// tokens), asi que desde ia.turn_log se distingue de un turno normal y queda auditable.
		Map<String, Object> row = this.jdbcTemplate.queryForMap("""
				SELECT status, handoff_reason, error_code, tokens_in, tokens_out
				FROM ia.turn_log WHERE turn_id = ?
				""", TURN_ID);
		assertThat(row).containsEntry("status", "HANDOFF").containsEntry("handoff_reason", "HEALTH_TOPIC")
				.containsEntry("tokens_in", 0).containsEntry("tokens_out", 0);
		assertThat(row.get("error_code")).isNull();
	}

	@Test
	@DisplayName("un turno normal deja el mensaje en la memoria del agente")
	void normalTurnWritesMemory() {
		post("¿Cuánto cuesta el masaje relajante?");

		// El asesor de memoria escribe dentro de la llamada al modelo; este test fija que hoy si escribe,
		// para que el siguiente no pueda pasar por casualidad.
		assertThat(count("ia.spring_ai_chat_memory")).isPositive();
	}

	@Test
	@DisplayName("un turno con handoff NO escribe en la memoria del agente (ADR 0013)")
	void handoffTurnDoesNotWriteMemory() {
		ResponseEntity<String> response = post("Estoy embarazada, puedo hacerme el masaje?");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		// El corte por HandoffPolicy ocurre antes de llamar al modelo, y la memoria la escribe
		// MessageChatMemoryAdvisor en before()/after() de esa llamada: con handoff no corre ninguno de los
		// dos. Si una actualizacion de Spring AI cambiara ese comportamiento, este test lo delata (y con el
		// asesor dejaria de ser cierto que el backend tiene que capturar el texto del turno derivado).
		assertThat(count("ia.spring_ai_chat_memory")).isZero();
	}

	@Test
	@DisplayName("la herramienta se ejecuta de verdad contra WireMock y queda en la tool call log")
	void executesTheCatalogueToolAgainstTheBackend() {
		BACKEND.stubFor(get(urlPathEqualTo(SERVICES_PATH)).willReturn(okJson(CATALOGUE_JSON)));

		ToolCallback tool = Arrays.stream(ToolCallbacks.from(this.customerTools))
				.filter(callback -> callback.getToolDefinition().name().equals("listarServicios"))
				.findFirst()
				.orElseThrow();
		new LoggingToolCallback(tool, this.toolCallLogger, this.objectMapper).call("{}");

		assertThat(count("ia.tool_call_log")).isEqualTo(1);
		assertThat(this.jdbcTemplate.queryForObject("SELECT status FROM ia.tool_call_log", String.class))
				.isEqualTo("OK");
	}

	@Test
	@DisplayName("sin el claim clientIp el turno se registra sin origen y no se le aplica el tope (ADR 0020)")
	void turnWithoutTheClaimIsRecordedWithoutOrigin() {
		// Token sin `clientIp`: el patron que emitia el backend antes de su PR #84 (variante tolerante).
		ResponseEntity<String> response = postWithoutOriginClaim("¿Cuánto cuesta el masaje relajante?");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(count("ia.turn_log")).isEqualTo(1);
		assertThat(count("ia.turn_log WHERE origin_hash IS NULL")).isEqualTo(1);
	}

	@Test
	@DisplayName("con el claim clientIp el tope por origen corta con 429 y scope origin, sin llamar al modelo (ADR 0020)")
	void originCapRejectsTurnsOverTheLimit() {
		// Dos turnos del mismo origen agotan el tope del test (saaspa.cost-guard.origin-max-turns=2).
		assertThat(post("¿Cuánto cuesta el masaje relajante?").getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(post("¿Y el de 90 minutos?").getStatusCode()).isEqualTo(HttpStatus.OK);
		int callsBefore = ScriptedChatModel.CALLS.get();

		// El tercero se rechaza antes de llamar al modelo (es el caso de H-04: el origen ya consumio su cupo).
		assertThatThrownBy(() -> post("¿Y el facial?"))
				.isInstanceOf(HttpClientErrorException.TooManyRequests.class)
				.satisfies(exception -> assertThat(
						((HttpClientErrorException) exception).getResponseBodyAsString())
						.contains("\"scope\":\"origin\"").contains("\"measure\":\"turns\""));

		assertThat(ScriptedChatModel.CALLS.get()).isEqualTo(callsBefore);
		// El turno rechazado no deja fila (ADR 0010) y las dos que hay llevan el origen hasheado.
		assertThat(count("ia.turn_log")).isEqualTo(2);
		assertThat(count("ia.turn_log WHERE origin_hash IS NULL")).isZero();
	}

	private ResponseEntity<String> post(String message) {
		return post(message, claims());
	}

	/** Turno con un token sin el claim {@code clientIp}: el patron del backend antes de su PR #84. */
	private ResponseEntity<String> postWithoutOriginClaim(String message) {
		Map<String, Object> claims = claims();
		claims.remove("clientIp");
		return post(message, claims);
	}

	private ResponseEntity<String> post(String message, Map<String, Object> claims) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("X-Internal-Api-Key", SERVICE_KEY);
		headers.setBearerAuth(TestTurnTokens.signValid(SIGNING_KEY, KID, claims));
		return this.restTemplate.postForEntity(url("/api/v1/chat"), new HttpEntity<>(body(message), headers),
				String.class);
	}

	private String url(String path) {
		return "http://localhost:" + this.port + path;
	}

	private static String body(String message) {
		return """
				{"turnId":"%s","tenantId":"kamerinos","conversationId":"conv-1","channel":"WEB_WIDGET",
				 "agent":"CLIENTAS","identity":{"kind":"ANONYMOUS"},"message":{"text":"%s"},
				 "locale":"es-CO","timezone":"America/Bogota","now":"2026-10-01T10:00:00-05:00"}
				""".formatted(TURN_ID, message);
	}

	private static Map<String, Object> claims() {
		Map<String, Object> claims = new LinkedHashMap<>(TestTurnTokens.defaultClaims());
		claims.put("jti", TURN_ID.toString());
		claims.put("agent", "CLIENTAS");
		claims.put("tenantId", "kamerinos");
		// Claim de origen (ADR 0020 / H-04): lo emite saaspa-backend (su PR #84) con la IP resuelta por su proxy.
		claims.put("clientIp", CLIENT_IP);
		return claims;
	}

	private static TurnToken turnToken() {
		// Turno anonimo con la IP que resuelve el backend (ADR 0020): el origen se guarda hasheado.
		return new TurnToken(TURN_ID.toString(), "kamerinos", "conv-1", TurnToken.Channel.WEB_WIDGET,
				TurnToken.Agent.CLIENTAS, null, null, CLIENT_IP, Instant.now().plusSeconds(300), "turn-token-it");
	}

	private int count(String table) {
		return this.jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ScriptedModelConfig {

		@Bean
		@Primary
		ChatModel scriptedChatModel() {
			return new ScriptedChatModel();
		}
	}

	/** Modelo guionizado: respuesta fija y contador de llamadas (no llama a un LLM real, R14). */
	static final class ScriptedChatModel implements ChatModel {

		static final String REPLY_TEXT = "El masaje relajante cuesta $ 120.000.";

		static final AtomicInteger CALLS = new AtomicInteger();

		@Override
		public ChatResponse call(Prompt prompt) {
			CALLS.incrementAndGet();
			ChatResponseMetadata metadata = ChatResponseMetadata.builder()
					.model("scripted-model")
					.usage(new DefaultUsage(10, 5))
					.build();
			return new ChatResponse(List.of(new Generation(new AssistantMessage(REPLY_TEXT))), metadata);
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.empty();
		}
	}
}