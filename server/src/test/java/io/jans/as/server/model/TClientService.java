/*
 * Janssen Project software is available under the Apache License (2004). See http://www.apache.org/licenses/ for full text.
 *
 * Copyright (c) 2020, Janssen Project
 */

package io.jans.as.server.model;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.net.URI;

import javax.ejb.Stateless;
import javax.inject.Named;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.jans.as.client.RegisterRequest;
import io.jans.as.client.RegisterResponse;
import io.jans.as.client.ws.rs.ClientTestUtil;
import io.jans.as.server.BaseTest;
import io.jans.as.server.register.ws.rs.RegisterRestWebService;
import io.jans.as.server.util.ServerUtil;

/**
 * @author Yuriy Zabrovarnyy
 */
@Stateless
@Named

public class TClientService {

    private TClientService() {
    }

    public static RegisterResponse register(RegisterRequest registerRequest, URI uri) throws JsonProcessingException {
        RegisterRestWebService registerWs = WebServiceFactory.instance().createRegisterWs(uri.toString());
        Response response = registerWs.requestRegister(ServerUtil.toPrettyJson(registerRequest.getJSONParameters()), null, null);
        String entity = response.readEntity(String.class);

        BaseTest.showResponse("TClientService", response, entity);

        assertEquals(response.getStatus(), 200, "Unexpected response code. " + entity);
        assertNotNull(entity, "Unexpected result: " + entity);
        final RegisterResponse registerResponse = RegisterResponse.valueOf(entity);
        ClientTestUtil.assert_(registerResponse);
        return registerResponse;
    }
}
