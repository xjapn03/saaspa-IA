package com.juanp.saaspa.ia.usage;

import java.util.Optional;
import java.util.UUID;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import com.juanp.saaspa.ia.security.CurrentTurnToken;
import com.juanp.saaspa.ia.security.TurnToken;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Envuelve una {@link ToolCallback} para auditar cada llamada en {@code ia.tool_call_log}.
 *
 * <p>Se mide la latencia, se guardan los argumentos y el resultado, y el estado se deduce del
 * resultado de la herramienta ({@code ok=false} se registra como error). El turno y el tenant salen
 * del turn token verificado (R1); si no hay token, no se audita nada.
 *
 * <p>Cualquier fallo al auditar se ignora: la conversacion nunca se interrumpe por trazabilidad.
 */
public class LoggingToolCallback implements ToolCallback {

	private final ToolCallback delegate;

	private final ToolCallLogger logger;

	private final ObjectMapper objectMapper;

	public LoggingToolCallback(ToolCallback delegate, ToolCallLogger logger, ObjectMapper objectMapper) {
		this.delegate = delegate;
		this.logger = logger;
		this.objectMapper = objectMapper;
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return this.delegate.getToolDefinition();
	}

	@Override
	public ToolMetadata getToolMetadata() {
		return this.delegate.getToolMetadata();
	}

	@Override
	public String call(String toolInput) {
		return call(toolInput, null);
	}

	@Override
	public String call(String toolInput, ToolContext toolContext) {
		long start = System.nanoTime();
		try {
			String result = toolContext == null ? this.delegate.call(toolInput)
					: this.delegate.call(toolInput, toolContext);
			record(toolInput, result, System.nanoTime() - start);
			return result;
		}
		catch (RuntimeException ex) {
			record(toolInput, null, System.nanoTime() - start);
			throw ex;
		}
	}

	private void record(String toolInput, String result, long elapsedNanos) {
		try {
			Optional<TurnToken> turnToken = CurrentTurnToken.find();
			if (turnToken.isEmpty()) {
				return;
			}
			ToolCallLogger.ToolCallStatus status = statusOf(result);
			this.logger.record(UUID.fromString(turnToken.get().turnId()), turnToken.get().tenantId(),
					getToolDefinition().name(), status, elapsedNanos / 1_000_000, toolInput, result);
		}
		catch (RuntimeException ex) {
			// La auditoria nunca interrumpe el turno.
		}
	}

	private ToolCallLogger.ToolCallStatus statusOf(String result) {
		if (result == null) {
			return ToolCallLogger.ToolCallStatus.ERROR;
		}
		try {
			JsonNode node = this.objectMapper.readTree(result);
			return node.path("ok").asBoolean(true) ? ToolCallLogger.ToolCallStatus.OK
					: ToolCallLogger.ToolCallStatus.ERROR;
		}
		catch (RuntimeException ex) {
			return ToolCallLogger.ToolCallStatus.OK;
		}
	}
}
