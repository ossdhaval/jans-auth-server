/*
 * Janssen Project software is available under the Apache License (2004). See http://www.apache.org/licenses/ for full text.
 *
 * Copyright (c) 2020, Janssen Project
 */

package io.jans.as.client.ws.rs;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import io.jans.as.client.AuthorizationRequest;
import io.jans.as.client.AuthorizationResponse;
import io.jans.as.client.BaseTest;
import io.jans.as.client.RegisterClient;
import io.jans.as.client.RegisterRequest;
import io.jans.as.client.RegisterResponse;
import io.jans.as.client.TokenClient;
import io.jans.as.client.TokenRequest;
import io.jans.as.client.TokenResponse;
import io.jans.as.model.common.AuthenticationMethod;
import io.jans.as.model.common.GrantType;
import io.jans.as.model.common.ResponseType;
import io.jans.as.model.common.SubjectType;
import io.jans.as.model.register.ApplicationType;
import io.jans.as.model.util.StringUtils;

/**
 * Test cases for the multi step authorization code flow (HTTP)
 *
 * @author Yuriy Movchan
 * @version November 29, 2017
 */
public class MultiStepAuthorizationCodeFlowHttpTest extends BaseTest {

    /**
     * Test for the complete multi-step Authorization Code Flow.
     */
    @Parameters({"userId", "userSecret", "redirectUris", "redirectUri", "sectorIdentifierUri"})
    @Test
    public void authorizationMultiStepCodeFlow(
            final String userId, final String userSecret, final String redirectUris, final String redirectUri,
            final String sectorIdentifierUri) throws Exception {
        showTitle("authorizationMultiStepCodeFlow");

        List<ResponseType> responseTypes = Arrays.asList(
                ResponseType.CODE,
                ResponseType.ID_TOKEN);
        List<String> scopes = Arrays.asList("openid", "profile", "address", "email", "phone", "user_name");

        // 1. Register client
        RegisterResponse registerResponse = registerClient(redirectUris, responseTypes, scopes, sectorIdentifierUri);

        String clientId = registerResponse.getClientId();
        String clientSecret = registerResponse.getClientSecret();

        // 2. Request authorization and receive the authorization code.
        String nonce = UUID.randomUUID().toString();
        AuthorizationResponse authorizationResponse = requestAuthorization(userId, userSecret, redirectUri, responseTypes, scopes, clientId, nonce, 2);

        String authorizationCode = authorizationResponse.getCode();

        assertNotNull(authorizationCode, "The authorization code is null");
        assertNotNull(authorizationResponse.getScope(), "The authorization scope is null");
        assertNotNull(authorizationResponse.getIdToken(), "The authorization id_token is null");

        // 3. Request access token using the authorization code.
        TokenRequest tokenRequest = new TokenRequest(GrantType.AUTHORIZATION_CODE);
        tokenRequest.setCode(authorizationCode);
        tokenRequest.setRedirectUri(redirectUri);
        tokenRequest.setAuthUsername(clientId);
        tokenRequest.setAuthPassword(clientSecret);
        tokenRequest.setAuthenticationMethod(AuthenticationMethod.CLIENT_SECRET_BASIC);

        TokenClient tokenClient1 = new TokenClient(tokenEndpoint);
        tokenClient1.setRequest(tokenRequest);
        TokenResponse tokenResponse1 = tokenClient1.exec();

        showClient(tokenClient1);
        assertEquals(tokenResponse1.getStatus(), 200, "Unexpected response code: " + tokenResponse1.getStatus());
        assertNotNull(tokenResponse1.getEntity(), "The entity is null");
        assertNotNull(tokenResponse1.getAccessToken(), "The access token is null");
        assertNotNull(tokenResponse1.getExpiresIn(), "The expires in value is null");
        assertNotNull(tokenResponse1.getTokenType(), "The token type is null");
        assertNotNull(tokenResponse1.getRefreshToken(), "The refresh token is null");
    }

    private AuthorizationResponse requestAuthorization(final String userId, final String userSecret, final String redirectUri,
                                                       List<ResponseType> responseTypes, List<String> scopes, String clientId, String nonce, int countAuthzSteps) {
        String state = UUID.randomUUID().toString();

        AuthorizationRequest authorizationRequest = new AuthorizationRequest(responseTypes, clientId, scopes, redirectUri, nonce);
        authorizationRequest.setState(state);

        AuthorizationResponse authorizationResponse = authenticateResourceOwnerAndGrantAccess(
                authorizationEndpoint, authorizationRequest, userId, userSecret,
                true, false, countAuthzSteps);

        assertNotNull(authorizationResponse.getLocation(), "The location is null");
        assertNotNull(authorizationResponse.getCode(), "The authorization code is null");
        assertNotNull(authorizationResponse.getState(), "The state is null");
        assertNotNull(authorizationResponse.getScope(), "The scope is null");
        return authorizationResponse;
    }

    private RegisterResponse registerClient(
            final String redirectUris, List<ResponseType> responseTypes, List<String> scopes, String sectorIdentifierUri) {
        RegisterRequest registerRequest = new RegisterRequest(ApplicationType.WEB, "jans test app",
                StringUtils.spaceSeparatedToList(redirectUris));
        registerRequest.setResponseTypes(responseTypes);
        registerRequest.setScope(scopes);
        registerRequest.setSubjectType(SubjectType.PAIRWISE);
        registerRequest.setSectorIdentifierUri(sectorIdentifierUri);

        RegisterClient registerClient = new RegisterClient(registrationEndpoint);
        registerClient.setRequest(registerRequest);
        RegisterResponse registerResponse = registerClient.exec();

        showClient(registerClient);
        assertEquals(registerResponse.getStatus(), 200, "Unexpected response code: " + registerResponse.getEntity());
        assertNotNull(registerResponse.getClientId());
        assertNotNull(registerResponse.getClientSecret());
        assertNotNull(registerResponse.getRegistrationAccessToken());
        assertNotNull(registerResponse.getClientIdIssuedAt());
        assertNotNull(registerResponse.getClientSecretExpiresAt());
        return registerResponse;
    }

}