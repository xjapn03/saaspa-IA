package com.juanp.saaspa.ia.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.security.KeyPair;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.juanp.saaspa.ia.agent.customer.CustomerAgent;
import com.juanp.saaspa.ia.backend.BackendUnavailableException;
import com.juanp.saaspa.ia.security.SecurityConfig;
import com.juanp.saaspa.ia.security.ServiceKeyVerifier;
import com.juanp.saaspa.ia.security.TestTurnTokens;
import com.juanp.saaspa.ia.security.TurnToken;
import com.juanp.saaspa.ia.usage.TurnLogService;

/**
 * Contrato de {@code POST /api/v1/chat} (T1.4): validacion del cuerpo, contraste con el turn token,
 * enrutado al agente, mapeo de la respuesta y errores con {@code ProblemDetail}.
 *
 * <p>El agente se sustituye por un doble (R14): no hay llamada a un LLM real ni al backend.
 */
@WebMvcTest(ChatController.class)
@Import(SecurityConfig.class)
class ChatControllerTest {

	private static final String SERVICE_KEY = "test-service-key";

	private static final String KID = "kid-current";

	private static final UUID TURN_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

	private static final KeyPair SIGNING_KEY = TestTurnTokens.generateKeyPair();

	@DynamicPropertySource
	static void securityProperties(DynamicPropertyRegistry registry) {
		registry.add("saaspa.chat-api.service-key", () -> SERVICE_KEY);
		registry.add("saaspa.turn-token.keys[0].kid", () -> KID);
		registry.add("saaspa.turn-token.keys[0].public-key", () -> TestTurnTokens.base64Pem(SIGNING_KEY.getPublic()));
	}

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CustomerAgent customerAgent;

	@MockitoBean
	private TurnLogService turnLogService;

	@Test
	@DisplayName("responde el turno del agente con reply, handoff y uso de tokens")
	void returnsAgentReply() throws Exception {
		given(this.customerAgent.reply(any(TurnToken.class), any(String.class)))
				.willReturn(new CustomerAgent.CustomerReply("El masaje relajante cuesta $ 120.000.",
						"customer-agent.v1", "deepseek-flash", 1200, 80));

		this.mockMvc.perform(post("/api/v1/chat").header(ServiceKeyVerifier.HEADER, SERVICE_KEY)
				.header("Authorization", bearer(claims("CLIENTAS", "kamerinos")))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson("kamerinos", "CLIENTAS", "Cuanto cuesta un masaje?")))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.turnId").value(TURN_ID.toString()))
				.andExpect(jsonPath("$.reply.text").value("El masaje relajante cuesta $ 120.000."))
				.andExpect(jsonPath("$.reply.links").isEmpty())
				.andExpect(jsonPath("$.handoff.requested").value(false))
				.andExpect(jsonPath("$.usage.model").value("deepseek-flash"))
				.andExpect(jsonPath("$.usage.tokensIn").value(1200))
				.andExpect(jsonPath("$.usage.tokensOut").value(80))
				.andExpect(jsonPath("$.sources").isEmpty());

		then(this.customerAgent).should().reply(any(TurnToken.class), eq("Cuanto cuesta un masaje?"));

		ArgumentCaptor<TurnLogService.TurnLog> turnLog = ArgumentCaptor.forClass(TurnLogService.TurnLog.class);
		then(this.turnLogService).should().record(turnLog.capture());
		assertThat(turnLog.getValue().turnId()).isEqualTo(TURN_ID);
		assertThat(turnLog.getValue().tenantId()).isEqualTo("kamerinos");
		assertThat(turnLog.getValue().conversationId()).isEqualTo("conv-1");
		assertThat(turnLog.getValue().channel()).isEqualTo("WEB_WIDGET");
		assertThat(turnLog.getValue().agent()).isEqualTo("CLIENTAS");
		assertThat(turnLog.getValue().promptVersion()).isEqualTo("customer-agent.v1");
		assertThat(turnLog.getValue().model()).isEqualTo("deepseek-flash");
		assertThat(turnLog.getValue().tokensIn()).isEqualTo(1200);
		assertThat(turnLog.getValue().tokensOut()).isEqualTo(80);
		assertThat(turnLog.getValue().latencyMs()).isGreaterThanOrEqualTo(0);
	}

	@Test
	@DisplayName("rechaza un cuerpo que no coincide con el turn token")
	void rejectsContextMismatch() throws Exception {
		this.mockMvc.perform(post("/api/v1/chat").header(ServiceKeyVerifier.HEADER, SERVICE_KEY)
				.header("Authorization", bearer(claims("CLIENTAS", "kamerinos")))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson("otro-tenant", "CLIENTAS", "Hola")))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.detail").value(containsString("tenantId")));

		then(this.customerAgent).shouldHaveNoInteractions();
		then(this.turnLogService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("rechaza un agente del cuerpo distinto al del turn token")
	void rejectsAgentMismatch() throws Exception {
		this.mockMvc.perform(post("/api/v1/chat").header(ServiceKeyVerifier.HEADER, SERVICE_KEY)
				.header("Authorization", bearer(claims("CLIENTAS", "kamerinos")))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson("kamerinos", "ADMIN", "Hola")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(containsString("agent")));

		then(this.customerAgent).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("el agente ADMIN responde 501 en la Fase 1")
	void rejectsAgentNotImplemented() throws Exception {
		this.mockMvc.perform(post("/api/v1/chat").header(ServiceKeyVerifier.HEADER, SERVICE_KEY)
				.header("Authorization", bearer(claims("ADMIN", "kamerinos")))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson("kamerinos", "ADMIN", "Cuanto vendimos hoy?")))
				.andExpect(status().isNotImplemented())
				.andExpect(jsonPath("$.title").value("No implementado"));

		then(this.customerAgent).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("rechaza un mensaje vacio con el detalle del campo")
	void rejectsBlankMessage() throws Exception {
		this.mockMvc.perform(post("/api/v1/chat").header(ServiceKeyVerifier.HEADER, SERVICE_KEY)
				.header("Authorization", bearer(claims("CLIENTAS", "kamerinos")))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson("kamerinos", "CLIENTAS", "")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0]", containsString("message.text")));

		then(this.customerAgent).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("rechaza una identidad del cuerpo que el token no respalda")
	void rejectsIdentityMismatch() throws Exception {
		String body = """
				{"turnId": "%s", "tenantId": "kamerinos", "conversationId": "conv-1", "channel": "WEB_WIDGET",
				 "agent": "CLIENTAS", "identity": {"kind": "USER", "userId": "user-7", "role": "CLIENTE"},
				 "message": {"text": "Muestrame mis citas"}, "locale": "es-CO",
				 "timezone": "America/Bogota", "now": "2026-10-01T10:00:00-05:00"}
				""".formatted(TURN_ID);

		this.mockMvc.perform(post("/api/v1/chat").header(ServiceKeyVerifier.HEADER, SERVICE_KEY)
				.header("Authorization", bearer(claims("CLIENTAS", "kamerinos")))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(containsString("identidad")));

		then(this.customerAgent).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("un backend caido se traduce a 502 con ProblemDetail")
	void mapsBackendFailureToBadGateway() throws Exception {
		willThrow(new BackendUnavailableException("/api/internal/v1/services", new IOException("sin conexion")))
				.given(this.customerAgent)
				.reply(any(TurnToken.class), any(String.class));

		this.mockMvc.perform(post("/api/v1/chat").header(ServiceKeyVerifier.HEADER, SERVICE_KEY)
				.header("Authorization", bearer(claims("CLIENTAS", "kamerinos")))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson("kamerinos", "CLIENTAS", "Que servicios tienen?")))
				.andExpect(status().isBadGateway())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Sistema de agenda no disponible"));
	}

	private static String bearer(Map<String, Object> claims) {
		return "Bearer " + TestTurnTokens.signValid(SIGNING_KEY, KID, claims);
	}

	private static Map<String, Object> claims(String agent, String tenantId) {
		Map<String, Object> claims = new LinkedHashMap<>(TestTurnTokens.defaultClaims());
		claims.put("jti", TURN_ID.toString());
		claims.put("agent", agent);
		claims.put("tenantId", tenantId);
		return claims;
	}

	private static String requestJson(String tenantId, String agent, String text) {
		return """
				{"turnId": "%s", "tenantId": "%s", "conversationId": "conv-1", "channel": "WEB_WIDGET",
				 "agent": "%s", "identity": {"kind": "ANONYMOUS"}, "message": {"text": "%s"},
				 "locale": "es-CO", "timezone": "America/Bogota", "now": "2026-10-01T10:00:00-05:00"}
				""".formatted(TURN_ID, tenantId, agent, text);
	}
}
