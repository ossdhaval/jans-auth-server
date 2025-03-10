/*
 * Janssen Project software is available under the Apache License (2004). See http://www.apache.org/licenses/ for full text.
 *
 * Copyright (c) 2020, Janssen Project
 */

package io.jans.as.client.load;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import io.jans.as.client.BaseTest;
import io.jans.as.client.RegisterClient;
import io.jans.as.client.RegisterRequest;
import io.jans.as.client.RegisterResponse;
import io.jans.as.model.register.ApplicationType;
import io.jans.as.model.util.StringUtils;

/**
 * DON'T INCLUDE IT IN TEST SUITE.
 *
 * @author Yuriy Zabrovarnyy
 * @version 0.9, 03/12/2013
 */

public class RegistrationLoadTest extends BaseTest {

    @Parameters({"redirectUris"})
    @Test(invocationCount = 1000, threadPoolSize = 100)
    public void registerClient(final String redirectUris) throws Exception {
        showTitle("requestClientAssociate1");

        RegisterClient registerClient = new RegisterClient(registrationEndpoint);
        RegisterResponse response = registerClient.execRegister(ApplicationType.WEB, "jans test app",
                StringUtils.spaceSeparatedToList(redirectUris));

        showClient(registerClient);
        assertEquals(response.getStatus(), 200, "Unexpected response code: " + response.getEntity());
        assertNotNull(response.getClientId());
        assertNotNull(response.getClientSecret());
        assertNotNull(response.getRegistrationAccessToken());
        assertNotNull(response.getClientSecretExpiresAt());

        RegisterRequest readClientRequest = new RegisterRequest(response.getRegistrationAccessToken());

        RegisterClient readClient = new RegisterClient(response.getRegistrationClientUri());
        readClient.setRequest(readClientRequest);
        RegisterResponse readClientResponse = readClient.exec();

        showClient(readClient);
        assertEquals(readClientResponse.getStatus(), 200, "Unexpected response code: " + readClientResponse.getEntity());
        assertNotNull(readClientResponse.getClientId());
        assertNotNull(readClientResponse.getClientSecret());
        assertNotNull(readClientResponse.getClientIdIssuedAt());
        assertNotNull(readClientResponse.getClientSecretExpiresAt());
    }
}
