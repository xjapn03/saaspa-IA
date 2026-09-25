package com.juanp.saaspa.ia.config;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

/**
 * Cliente HTTP dedicado al modelo (DeepSeek).
 *
 * <p>Se declara un {@link RestClient.Builder} propio con timeouts explicitos (connect/read) para la
 * llamada al LLM. Al ser el unico {@code RestClient.Builder} del contexto, lo recoge la autoconfig de
 * DeepSeek; el cliente del backend sigue aplicando sus propios timeouts en {@code BackendClientConfig},
 * que no se tocan aqui (regla: cada cliente externo con su timeout, AGENTS.md seccion 8).
 *
 * <p>El streaming (WebClient) no se usa en la Fase 1: {@code ChatController} llama con {@code .call()}
 * (D-STREAM, diferido); cuando se active el streaming habra que darle tambien timeouts explicitos.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmProperties.class)
public class LlmClientConfig {

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public RestClient.Builder llmRestClientBuilder(LlmProperties properties) {
		HttpClientSettings settings = HttpClientSettings.defaults()
				.withConnectTimeout(properties.connectTimeout())
				.withReadTimeout(properties.readTimeout());
		return RestClient.builder()
				.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
	}
}