/*
 * Janssen Project software is available under the Apache License (2004). See http://www.apache.org/licenses/ for full text.
 *
 * Copyright (c) 2020, Janssen Project
 */

package io.jans.as.client.interop;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.util.Arrays;

import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import io.jans.as.client.BaseTest;
import io.jans.as.client.RegisterClient;
import io.jans.as.client.RegisterRequest;
import io.jans.as.client.RegisterResponse;
import io.jans.as.model.common.AuthenticationMethod;
import io.jans.as.model.common.SubjectType;
import io.jans.as.model.crypto.signature.SignatureAlgorithm;
import io.jans.as.model.register.ApplicationType;
import io.jans.as.model.util.StringUtils;

/**
 * OC5:FeatureTest-Enables Dynamic Registration
 *
 * @author Javier Rojas Blum Date: 07.27.2013
 */
public class EnablesDynamicRegistration extends BaseTest {

    @Parameters({"redirectUris", "sectorIdentifierUri", "clientJwksUri"})
    @Test
    public void enablesDynamicRegistration(final String redirectUris, final String sectorIdentifierUri,
                                           final String clientJwksUri) throws Exception {
        showTitle("OC5:FeatureTest-Enables Dynamic Registration");

        RegisterRequest registerRequest = new RegisterRequest(ApplicationType.WEB, "jans test app",
                StringUtils.spaceSeparatedToList(redirectUris));
        registerRequest.setContacts(Arrays.asList("javier@gluu.org", "javier.rojas.blum@gmail.com"));
        registerRequest.setLogoUri("http://www.gluu.org/wp-content/themes/gluursn/images/logo.png");
        registerRequest.setTokenEndpointAuthMethod(AuthenticationMethod.CLIENT_SECRET_JWT);
        registerRequest.setPolicyUri("http://www.gluu.org/policy");
        registerRequest.setJwksUri(clientJwksUri);
        registerRequest.setSectorIdentifierUri(sectorIdentifierUri);
        registerRequest.setSubjectType(SubjectType.PUBLIC);
        registerRequest.setRequestObjectSigningAlg(SignatureAlgorithm.RS256);

        RegisterClient registerClient = new RegisterClient(registrationEndpoint);
        registerClient.setRequest(registerRequest);
        RegisterResponse response = registerClient.exec();

        showClient(registerClient);
        assertEquals(response.getStatus(), 200, "Unexpected response code: " + response.getEntity());
        assertNotNull(response.getClientId());
        assertNotNull(response.getClientSecret());
        assertNotNull(response.getRegistrationAccessToken());
        assertNotNull(response.getRegistrationClientUri());
        assertNotNull(response.getClientIdIssuedAt());
        assertNotNull(response.getClientSecretExpiresAt());
    }
}