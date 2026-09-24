package com.juanp.saaspa.ia.security;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import com.juanp.saaspa.ia.config.ChatApiProperties;
import com.juanp.saaspa.ia.config.TurnTokenProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * Seguridad HTTP del servicio.
 *
 * <ul>
 *   <li>{@code /actuator/**}: abierto (sondas del contenedor; la exposicion ya esta limitada a
 *       {@code health,info} en {@code application.yml});
 *   <li>{@code /api/**}: exige clave de servicio (cabecera {@code X-Internal-Api-Key}) y turn token
 *       verificado (ADR 0006). Sin estado: cada peticion trae su token;
 *   <li>el resto: cerrado, salvo los despachos de error y forward que necesita Spring MVC.
 * </ul>
 *
 * <p>No se conceden autoridades en la Fase 1: NestJS autoriza con el mismo turn token (regla R2).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ TurnTokenProperties.class, ChatApiProperties.class })
public class SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/actuator/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain chatApiSecurityFilterChain(HttpSecurity http, JwtDecoder turnTokenDecoder,
            TurnTokenAuthenticationConverter turnTokenAuthenticationConverter, ServiceKeyVerifier serviceKeyVerifier,
            ProblemDetailSecurityHandler securityHandler) throws Exception {
        http.securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(securityHandler)
                        .jwt(jwt -> jwt.decoder(turnTokenDecoder)
                                .jwtAuthenticationConverter(turnTokenAuthenticationConverter)))
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(securityHandler)
                        .accessDeniedHandler(securityHandler))
                .addFilterBefore(new ServiceKeyAuthenticationFilter(serviceKeyVerifier, securityHandler),
                        BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain fallbackSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    public JwtDecoder turnTokenDecoder(TurnTokenProperties turnTokenProperties) {
        return TurnTokenDecoderFactory.create(turnTokenProperties);
    }

    @Bean
    public TurnTokenAuthenticationConverter turnTokenAuthenticationConverter() {
        return new TurnTokenAuthenticationConverter();
    }

    @Bean
    public ServiceKeyVerifier serviceKeyVerifier(ChatApiProperties chatApiProperties) {
        return new ServiceKeyVerifier(chatApiProperties.serviceKey());
    }

    @Bean
    public ProblemDetailSecurityHandler problemDetailSecurityHandler(ObjectMapper objectMapper) {
        return new ProblemDetailSecurityHandler(objectMapper);
    }
}
