package com.juanp.saaspa.ia.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.juanp.saaspa.ia.TestcontainersConfiguration;

/**
 * Cadena de seguridad completa (T1.1): clave de servicio de entrada, turn token verificado,
 * respuestas {@code ProblemDetail} y rutas abiertas.
 */
@SpringBootTest(properties = "spring.ai.deepseek.api-key=test-key")
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, ChatApiSecurityTest.TestChatController.class })
class ChatApiSecurityTest {

	private static final String SERVICE_KEY = "test-service-key";

	private static final String KID = "kid-current";

	private static final KeyPair SIGNING_KEY = TestTurnTokens.generateKeyPair();

	@DynamicPropertySource
	static void securityProperties(DynamicPropertyRegistry registry) {
		registry.add("saaspa.chat-api.service-key", () -> SERVICE_KEY);
		registry.add("saaspa.turn-token.keys[0].kid", () -> KID);
		registry.add("saaspa.turn-token.keys[0].public-key", () -> TestTurnTokens.base64Pem(SIGNING_KEY.getPublic()));
	}

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("la sonda del actuator sigue abierta")
	void actuatorHealthIsOpen() throws Exception {
		this.mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("sin clave de servicio: 401 con ProblemDetail")
	void rejectsWithoutServiceKey() throws Exception {
		this.mockMvc.perform(post("/api/v1/chat").header("Authorization", bearerValidToken()))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.title").value("No autorizado"));
	}

	@Test
	@DisplayName("con clave de servicio invalida: 401")
	void rejectsWithWrongServiceKey() throws Exception {
		this.mockMvc.perform(post("/api/v1/chat")
				.header(ServiceKeyVerifier.HEADER, "otra-clave")
				.header("Authorization", bearerValidToken()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("sin turn token: 401")
	void rejectsWithoutTurnToken() throws Exception {
		this.mockMvc.perform(post("/api/v1/chat").header(ServiceKeyVerifier.HEADER, SERVICE_KEY))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("con turn token caducado: 401")
	void rejectsExpiredTurnToken() throws Exception {
		String expired = TestTurnTokens.sign(SIGNING_KEY.getPrivate(), KID, Instant.now().minusSeconds(60),
				TestTurnTokens.defaultClaims());

		this.mockMvc.perform(post("/api/v1/chat")
				.header(ServiceKeyVerifier.HEADER, SERVICE_KEY)
				.header("Authorization", "Bearer " + expired))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("con clave y turn token validos: 200 con la identidad del turno")
	void acceptsValidServiceKeyAndTurnToken() throws Exception {
		this.mockMvc.perform(post("/api/v1/chat")
				.header(ServiceKeyVerifier.HEADER, SERVICE_KEY)
				.header("Authorization", bearerValidToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenantId").value("kamerinos"))
				.andExpect(jsonPath("$.channel").value("WEB_WIDGET"))
				.andExpect(jsonPath("$.anonymous").value(true));
	}

	@Test
	@DisplayName("fuera de la API y del actuator: rechazado")
	void rejectsOtherPaths() throws Exception {
		this.mockMvc.perform(get("/")).andExpect(status().is4xxClientError());
	}

	private static String bearerValidToken() {
		return "Bearer " + TestTurnTokens.signValid(SIGNING_KEY, KID, TestTurnTokens.defaultClaims());
	}

	/** Controlador de prueba que devuelve la identidad del turno verificado. */
	@RestController
	static class TestChatController {

		@PostMapping("/api/v1/chat")
		Map<String, Object> chat() {
			TurnToken turnToken = CurrentTurnToken.require();
			return Map.of("tenantId", turnToken.tenantId(), "channel", turnToken.channel().name(), "anonymous",
					turnToken.isAnonymous());
		}

	}

}
