package com.juanp.saaspa.ia.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Exige la clave de servicio de entrada antes de evaluar el turn token.
 *
 * <p>Se ejecuta delante de {@code BearerTokenAuthenticationFilter}: sin clave valida no se intenta
 * verificar ningun token. Si la clave no esta configurada, rechaza la peticion (fallo cerrado).
 */
public class ServiceKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ServiceKeyVerifier verifier;

    private final ProblemDetailSecurityHandler handler;

    public ServiceKeyAuthenticationFilter(ServiceKeyVerifier verifier, ProblemDetailSecurityHandler handler) {
        this.verifier = verifier;
        this.handler = handler;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!this.verifier.isValid(request.getHeader(ServiceKeyVerifier.HEADER))) {
            this.handler.unauthorized(response, this.verifier.isConfigured()
                    ? "Clave de servicio ausente o invalida"
                    : "El servicio no tiene configurada la clave de servicio de entrada");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
