package com.juanp.saaspa.ia.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

/**
 * ADR 0009: la llamada al modelo usa reintentos acotados (no el default de 10) y timeouts HTTP
 * explicitos. El {@code RetryTemplate} sale de {@code SpringAiRetryAutoConfiguration} con los valores
 * de {@code spring.ai.retry.*}; el {@code RestClient.Builder} sale de {@code LlmClientConfig}. El
 * proveedor se sustituye por WireMock (regla R14: no se llama a ningun LLM real).
 */
class LlmTimeoutRetryTest {

	private static final String COMPLETIONS_PATH = "/chat/completions";

	private static WireMockServer deepseek;

	@BeforeAll
	static void startDeepSeek() {
		deepseek = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		deepseek.start();
	}

	@AfterAll
	static void stopDeepSeek() {
		deepseek.stop();
	}

	@BeforeEach
	void resetStubs() {
		deepseek.resetAll();
	}

	@Test
	@DisplayName("el RetryTemplate queda acotado a 2 intentos con backoff corto")
	void retryTemplateUsesBoundedBackoff() {
		retryRunner().run(context -> {
			assertThat(context).hasNotFailed();

			RetryTemplate template = context.getBean(RetryTemplate.class);
			assertThat(template.getRetryPolicy().getBackOff()).isInstanceOf(ExponentialBackOff.class);

			ExponentialBackOff backOff = (ExponentialBackOff) template.getRetryPolicy().getBackOff();
			assertThat(backOff.getMaxAttempts()).isEqualTo(2);
			assertThat(backOff.getInitialInterval()).isEqualTo(500);
			assertThat(backOff.getMultiplier()).isEqualTo(2.0);
			assertThat(backOff.getMaxInterval()).isEqualTo(2000);
		});
	}

	@Test
	@DisplayName("un 500 del proveedor se reintenta 2 veces (3 peticiones en total, no 10)")
	void retriesFiveHundredBounded() {
		deepseek.stubFor(post(urlPathEqualTo(COMPLETIONS_PATH))
				.willReturn(aResponse()
						.withStatus(500)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"error\":{\"message\":\"boom\"}}")));

		retryRunner().run(context -> {
			assertThatThrownBy(() -> model(context).call(new Prompt("Hola")))
					.isInstanceOf(RuntimeException.class);

			assertThat(deepseek.getAllServeEvents().size()).isEqualTo(3);
		});
	}

	@Test
	@DisplayName("un modelo colgado termina en error acotado, no en 10 reintentos")
	void hangingModelFailsWithinBound() {
		deepseek.stubFor(post(urlPathEqualTo(COMPLETIONS_PATH))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"choices\":[{\"message\":{\"content\":\"tarde\"}}]}")
						.withFixedDelay(5000)));

		retryRunner().run(context -> {
			long start = System.nanoTime();
			assertThatThrownBy(() -> model(context).call(new Prompt("Hola")))
					.isInstanceOf(RuntimeException.class);
			long elapsedMs = (System.nanoTime() - start) / 1_000_000;

			assertThat(elapsedMs).isLessThan(8000);
			assertThat(deepseek.getAllServeEvents().size()).isEqualTo(3);
		});
	}

	@Test
	@DisplayName("un 429 (transitorio) se reintenta")
	void retriesTransientRateLimit() {
		deepseek.stubFor(post(urlPathEqualTo(COMPLETIONS_PATH))
				.willReturn(aResponse()
						.withStatus(429)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"error\":{\"message\":\"rate limit\"}}")));

		retryRunner().run(context -> {
			assertThatThrownBy(() -> model(context).call(new Prompt("Hola")))
					.isInstanceOf(RuntimeException.class);

			assertThat(deepseek.getAllServeEvents().size()).isEqualTo(3);
		});
	}

	@Test
	@DisplayName("un 400 (error del cliente) no se reintenta")
	void doesNotRetryBadRequest() {
		deepseek.stubFor(post(urlPathEqualTo(COMPLETIONS_PATH))
				.willReturn(aResponse()
						.withStatus(400)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"error\":{\"message\":\"bad request\"}}")));

		retryRunner().run(context -> {
			assertThatThrownBy(() -> model(context).call(new Prompt("Hola")))
					.isInstanceOf(RuntimeException.class);

			assertThat(deepseek.getAllServeEvents().size()).isEqualTo(1);
		});
	}

	private ApplicationContextRunner retryRunner() {
		return new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(SpringAiRetryAutoConfiguration.class))
				.withUserConfiguration(LlmClientConfig.class)
				.withPropertyValues(
						"spring.ai.retry.max-attempts=2",
						"spring.ai.retry.backoff.initial-interval=500ms",
						"spring.ai.retry.backoff.multiplier=2",
						"spring.ai.retry.backoff.max-interval=2s",
						"spring.ai.retry.on-http-codes=408,429",
						"spring.ai.retry.exclude-on-http-codes=400,401,403,404,409,422",
						"saaspa.llm.connect-timeout=2s",
						"saaspa.llm.read-timeout=500ms");
	}

	private static DeepSeekChatModel model(org.springframework.context.ApplicationContext context) {
		DeepSeekApi api = DeepSeekApi.builder()
				.baseUrl(deepseek.baseUrl())
				.apiKey("test-key")
				.restClientBuilder(context.getBean(RestClient.Builder.class))
				.responseErrorHandler(context.getBean(ResponseErrorHandler.class))
				.build();
		return DeepSeekChatModel.builder()
				.deepSeekApi(api)
				.retryTemplate(context.getBean(RetryTemplate.class))
				.build();
	}
}