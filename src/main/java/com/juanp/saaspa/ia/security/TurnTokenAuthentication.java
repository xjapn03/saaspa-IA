package com.juanp.saaspa.ia.security;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/**
 * {@code Authentication} de un turno ya verificado: el principal es el {@link TurnToken}.
 *
 * <p>En la Fase 1 no se conceden autoridades: los permisos los decide NestJS con el mismo turn
 * token (regla R2). Aqui solo se garantiza que la peticion trae una identidad firmada.
 */
public class TurnTokenAuthentication extends AbstractAuthenticationToken {

    private final transient TurnToken turnToken;

    public TurnTokenAuthentication(TurnToken turnToken) {
        super(List.<GrantedAuthority>of());
        this.turnToken = turnToken;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return this.turnToken.rawToken();
    }

    @Override
    public Object getPrincipal() {
        return this.turnToken;
    }

    @Override
    public String getName() {
        return this.turnToken.userId() != null ? this.turnToken.userId() : this.turnToken.conversationId();
    }
}
