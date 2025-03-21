/*
 * Janssen Project software is available under the Apache License (2004). See http://www.apache.org/licenses/ for full text.
 *
 * Copyright (c) 2020, Janssen Project
 */

package io.jans.as.server.jwk.ws.rs;

import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.slf4j.Logger;

import io.jans.as.model.config.WebKeysConfiguration;
import io.jans.as.model.configuration.AppConfiguration;
import io.jans.as.model.jwk.JSONWebKey;

/**
 * Provides interface for JWK REST web services
 *
 * @author Javier Rojas Blum
 * @version June 15, 2016
 */
@Path("/")
public class JwkRestWebServiceImpl implements JwkRestWebService {

    @Inject
    private Logger log;

    @Inject
    private AppConfiguration appConfiguration;

    @Inject
    private WebKeysConfiguration webKeysConfiguration;

    @Override
    public Response requestJwk(SecurityContext sec) {
        log.debug("Attempting to request JWK, Is Secure = {}", sec.isSecure());
        Response.ResponseBuilder builder = Response.ok();

        try {
            WebKeysConfiguration webKeysConfiguration = new WebKeysConfiguration();
            webKeysConfiguration.setKeys(this.filterKeys(this.webKeysConfiguration.getKeys()));
            builder.entity(webKeysConfiguration.toString());
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            builder = Response.status(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode()); // 500
        }

        return builder.build();
    }

    /**
     * Method responsible to filter keys and return a new list of keys with all
     * algorithms that it is inside Json config attribute called "jwksAlgorithmsSupported"
     * @param allKeys All keys that should be filtered
     * @return Filtered list
     */
    private List<JSONWebKey> filterKeys(List<JSONWebKey> allKeys) {
        List<String> jwksAlgorithmsSupported = appConfiguration.getJwksAlgorithmsSupported();
        if (allKeys == null || allKeys.size() == 0
                || jwksAlgorithmsSupported == null || jwksAlgorithmsSupported.size() == 0) {
            return allKeys;
        }
        return allKeys.stream().filter(
                (key) -> jwksAlgorithmsSupported.contains(key.getAlg().getParamName())
        ).collect(Collectors.toList());
    }

}