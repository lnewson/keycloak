/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.keycloak.services.resources.flows;

import org.jboss.logging.Logger;
import org.jboss.resteasy.spi.HttpRequest;
import org.keycloak.OAuth2Constants;
import org.keycloak.audit.Audit;
import org.keycloak.audit.Details;
import org.keycloak.audit.EventType;
import org.keycloak.models.ApplicationModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RequiredCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserModel.RequiredAction;
import org.keycloak.models.UserSessionModel;
import org.keycloak.provider.ProviderSession;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.services.managers.AccessCodeEntry;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.TokenManager;
import org.keycloak.util.Time;

import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import java.util.HashSet;
import java.util.Set;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class OAuthFlows {

    private static final Logger log = Logger.getLogger(OAuthFlows.class);

    private final ProviderSession providerSession;

    private final RealmModel realm;

    private final HttpRequest request;

    private final UriInfo uriInfo;

    private final AuthenticationManager authManager;

    private final TokenManager tokenManager;

    OAuthFlows(ProviderSession providerSession, RealmModel realm, HttpRequest request, UriInfo uriInfo, AuthenticationManager authManager,
            TokenManager tokenManager) {
        this.providerSession = providerSession;
        this.realm = realm;
        this.request = request;
        this.uriInfo = uriInfo;
        this.authManager = authManager;
        this.tokenManager = tokenManager;
    }

    public Response redirectAccessCode(AccessCodeEntry accessCode, UserSessionModel session, String state, String redirect) {
        return redirectAccessCode(accessCode, session, state, redirect, false);
    }


    public Response redirectAccessCode(AccessCodeEntry accessCode, UserSessionModel session, String state, String redirect, boolean rememberMe) {
        String code = accessCode.getCode();
        UriBuilder redirectUri = UriBuilder.fromUri(redirect).queryParam(OAuth2Constants.CODE, code);
        log.debugv("redirectAccessCode: state: {0}", state);
        if (state != null)
            redirectUri.queryParam(OAuth2Constants.STATE, state);
        Response.ResponseBuilder location = Response.status(302).location(redirectUri.build());
        Cookie remember = request.getHttpHeaders().getCookies().get(AuthenticationManager.KEYCLOAK_REMEMBER_ME);
        rememberMe = rememberMe || remember != null;
        // refresh the cookies!
        authManager.createLoginCookie(realm, accessCode.getUser(), session, uriInfo, rememberMe);
        if (rememberMe) authManager.createRememberMeCookie(realm, uriInfo);
        return location.build();
    }

    public Response redirectError(ClientModel client, String error, String state, String redirect) {
        UriBuilder redirectUri = UriBuilder.fromUri(redirect).queryParam(OAuth2Constants.ERROR, error);
        if (state != null) {
            redirectUri.queryParam(OAuth2Constants.STATE, state);
        }
        return Response.status(302).location(redirectUri.build()).build();
    }

    public Response processAccessCode(String scopeParam, String state, String redirect, ClientModel client, UserModel user, UserSessionModel session, String username, boolean rememberMe, String authMethod, Audit audit) {
        isTotpConfigurationRequired(user);
        isEmailVerificationRequired(user);

        boolean isResource = client instanceof ApplicationModel;
        AccessCodeEntry accessCode = tokenManager.createAccessCode(scopeParam, state, redirect, realm, client, user, session);
        accessCode.setUsername(username);
        accessCode.setRememberMe(rememberMe);
        accessCode.setAuthMethod(authMethod);

        log.debugv("processAccessCode: isResource: {0}", isResource);
        log.debugv("processAccessCode: go to oauth page?: {0}",
                (!isResource && (accessCode.getRealmRolesRequested().size() > 0 || accessCode.getResourceRolesRequested()
                        .size() > 0)));

        audit.detail(Details.CODE_ID, accessCode.getId());

        Set<RequiredAction> requiredActions = user.getRequiredActions();
        if (!requiredActions.isEmpty()) {
            accessCode.setRequiredActions(new HashSet<UserModel.RequiredAction>(requiredActions));
            accessCode.setExpiration(Time.currentTime() + realm.getAccessCodeLifespanUserAction());

            RequiredAction action = user.getRequiredActions().iterator().next();
            if (action.equals(RequiredAction.VERIFY_EMAIL)) {
                audit.clone().event(EventType.SEND_VERIFY_EMAIL).detail(Details.EMAIL, accessCode.getUser().getEmail()).success();
            }

            return Flows.forms(providerSession, realm, uriInfo).setAccessCode(accessCode.getId(), accessCode.getCode()).setUser(user)
                    .createResponse(action);
        }

        if (!isResource
                && (accessCode.getRealmRolesRequested().size() > 0 || accessCode.getResourceRolesRequested().size() > 0)) {
            accessCode.setExpiration(Time.currentTime() + realm.getAccessCodeLifespanUserAction());
            return Flows.forms(providerSession, realm, uriInfo).setAccessCode(accessCode.getId(), accessCode.getCode()).
                    setAccessRequest(accessCode.getRealmRolesRequested(), accessCode.getResourceRolesRequested()).
                    setClient(client).createOAuthGrant();
        }

        if (redirect != null) {
            audit.success();
            return redirectAccessCode(accessCode, session, state, redirect, rememberMe);
        } else {
            return null;
        }
    }

    public Response forwardToSecurityFailure(String message) {
        return Flows.forms(providerSession, realm, uriInfo).setError(message).createErrorPage();
    }

    private void isTotpConfigurationRequired(UserModel user) {
        for (RequiredCredentialModel c : realm.getRequiredCredentials()) {
            if (c.getType().equals(CredentialRepresentation.TOTP) && !user.isTotp()) {
                user.addRequiredAction(RequiredAction.CONFIGURE_TOTP);
                log.debug("User is required to configure totp");
            }
        }
    }

    private void isEmailVerificationRequired(UserModel user) {
        if (realm.isVerifyEmail() && !user.isEmailVerified()) {
            user.addRequiredAction(RequiredAction.VERIFY_EMAIL);
            log.debug("User is required to verify email");
        }
    }

}
