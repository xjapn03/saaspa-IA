package com.juanp.saaspa.ia.agent.customer;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

/**
 * {@code ChatModel} doble para los tests: guarda los prompts recibidos y devuelve una respuesta fija.
 * Ningun test de este repositorio llama a un LLM real (regla R14).
 */
final class TestChatModel implements ChatModel {

	private final List<Prompt> prompts = new ArrayList<>();

	private String replyText = "Claro, con gusto te ayudo.";

	void replyWith(String text) {
		this.replyText = text;
	}

	List<Prompt> prompts() {
		return this.prompts;
	}

	Prompt lastPrompt() {
		return this.prompts.get(this.prompts.size() - 1);
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		this.prompts.add(prompt);
		ChatResponseMetadata metadata = ChatResponseMetadata.builder()
				.model("test-model")
				.usage(new DefaultUsage(11, 7))
				.build();
		return new ChatResponse(List.of(new Generation(new AssistantMessage(this.replyText))), metadata);
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		return Flux.empty();
	}
}
