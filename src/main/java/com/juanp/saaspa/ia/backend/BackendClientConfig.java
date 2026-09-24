package com.juanp.saaspa.ia.backend;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.juanp.saaspa.ia.config.BackendProperties;

/**
 * Configuracion del cliente HTTP hacia NestJS.
 *
 * <p>Los timeouts se aplican con {@code HttpClientSettings} de Spring Boot 4.1 y son explicitos
 * (AGENTS.md, seccion 8). El nombre del bean de {@link RestClient} se declara como constante para
 * poder calificarlo en las inyecciones.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BackendProperties.class)
public class BackendClientConfig {

	/** Cabecera de servicio en la direccion IA -&gt; NestJS (valor de {@code INTERNAL_API_KEY}). */
	public static final String SERVICE_KEY_HEADER = "X-Internal-Api-Key";

	/** Nombre del bean {@link RestClient} que apunta al backend. */
	public static final String BACKEND_REST_CLIENT = "backendRestClient";

	@Bean(BACKEND_REST_CLIENT)
	public RestClient backendRestClient(RestClient.Builder builder, BackendProperties properties) {
		HttpClientSettings settings = HttpClientSettings.defaults()
				.withConnectTimeout(properties.connectTimeout())
				.withReadTimeout(properties.readTimeout());

		RestClient.Builder configured = builder
				.baseUrl(properties.baseUrl())
				.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));

		if (StringUtils.hasText(properties.internalApiKey())) {
			configured = configured.defaultHeader(SERVICE_KEY_HEADER, properties.internalApiKey());
		}
		return configured.build();
	}

	@Bean
	public BackendClient backendClient(@Qualifier(BACKEND_REST_CLIENT) RestClient restClient) {
		return new BackendClient(restClient);
	}
}
