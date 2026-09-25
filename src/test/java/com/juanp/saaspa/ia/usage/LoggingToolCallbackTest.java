package com.juanp.saaspa.ia.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.security.core.context.SecurityContextHolder;

import com.juanp.saaspa.ia.security.TurnToken;
import com.juanp.saaspa.ia.security.TurnTokenAuthentication;

import tools.jackson.databind.json.JsonMapper;

/**
 * Auditoria de tool calls (T1.6): delegacion, estado segun el resultado y tolerancia a fallos.
 */
class LoggingToolCallbackTest {

	private static final UUID TURN_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

	private static final String TOOL_NAME = "listarServicios";

	private static final String ARGS = "{\"destacados\":true}";

	private final ToolCallLogger logger = mock(ToolCallLogger.class);

	private final StubToolCallback delegate = new StubToolCallback();

	private final LoggingToolCallback callback = new LoggingToolCallback(this.delegate, this.logger,
			JsonMapper.builder().build());

	@BeforeEach
	void authenticate() {
		SecurityContextHolder.getContext()
				.setAuthentication(new TurnTokenAuthentication(new TurnToken(TURN_ID.toString(), "kamerinos", "conv-1",
						TurnToken.Channel.WEB_WIDGET, TurnToken.Agent.CLIENTAS, null, null,
						Instant.now().plusSeconds(300), "turn-token-123")));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("delega la llamada y audita el resultado correcto")
	void logsOkStatus() {
		this.delegate.result = "{\"ok\":true,\"services\":[]}";

		String result = this.callback.call(ARGS);

		assertThat(result).isEqualTo("{\"ok\":true,\"services\":[]}");
		then(this.logger).should().record(eq(TURN_ID), eq("kamerinos"), eq(TOOL_NAME),
				eq(ToolCallLogger.ToolCallStatus.OK), anyLong(), eq(ARGS), eq("{\"ok\":true,\"services\":[]}"));
	}

	@Test
	@DisplayName("audita estado ERROR cuando la herramienta devuelve ok=false")
	void logsErrorWhenToolReportsFailure() {
		this.delegate.result = "{\"ok\":false,\"message\":\"El sistema de agenda no respondio en este momento\"}";

		this.callback.call(ARGS);

		then(this.logger).should().record(eq(TURN_ID), eq("kamerinos"), eq(TOOL_NAME),
				eq(ToolCallLogger.ToolCallStatus.ERROR), anyLong(), eq(ARGS), any(String.class));
	}

	@Test
	@DisplayName("audita estado ERROR y propaga la excepcion de la herramienta")
	void logsErrorAndRethrows() {
		this.delegate.failure = new IllegalStateException("fallo inesperado");

		assertThatThrownBy(() -> this.callback.call(ARGS)).isInstanceOf(IllegalStateException.class);

		then(this.logger).should().record(eq(TURN_ID), eq("kamerinos"), eq(TOOL_NAME),
				eq(ToolCallLogger.ToolCallStatus.ERROR), anyLong(), eq(ARGS), isNull());
	}

	@Test
	@DisplayName("sin turn token verificado no audita nada")
	void doesNotLogWithoutTurnToken() {
		SecurityContextHolder.clearContext();
		this.delegate.result = "{\"ok\":true}";

		assertThat(this.callback.call(ARGS)).isEqualTo("{\"ok\":true}");
		then(this.logger).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("un fallo al auditar no interrumpe la herramienta")
	void neverFailsWhenLoggerFails() {
		this.delegate.result = "{\"ok\":true}";
		willThrow(new IllegalStateException("db caida")).given(this.logger)
				.record(any(UUID.class), any(String.class), any(String.class),
						any(ToolCallLogger.ToolCallStatus.class), anyLong(), any(), any());

		assertThat(this.callback.call(ARGS)).isEqualTo("{\"ok\":true}");
	}

	@Test
	@DisplayName("expone la definicion y los metadatos de la herramienta envuelta")
	void delegatesDefinitionAndMetadata() {
		assertThat(this.callback.getToolDefinition()).isSameAs(this.delegate.definition);
		assertThat(this.callback.getToolMetadata()).isSameAs(this.delegate.metadata);
	}

	/** Doble de herramienta: devuelve un JSON fijo, falla si se le pide o solo devuelve lo recibido. */
	private static final class StubToolCallback implements ToolCallback {

		private final ToolDefinition definition = mock(ToolDefinition.class);

		private final ToolMetadata metadata = mock(ToolMetadata.class);

		private String result = "{\"ok\":true}";

		private RuntimeException failure;

		@Override
		public ToolDefinition getToolDefinition() {
			given(this.definition.name()).willReturn(TOOL_NAME);
			return this.definition;
		}

		@Override
		public ToolMetadata getToolMetadata() {
			return this.metadata;
		}

		@Override
		public String call(String toolInput) {
			if (this.failure != null) {
				throw this.failure;
			}
			return this.result;
		}

		@Override
		public String call(String toolInput, ToolContext toolContext) {
			return call(toolInput);
		}
	}
}
