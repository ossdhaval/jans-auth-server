/*
 * Janssen Project software is available under the Apache License (2004). See http://www.apache.org/licenses/ for full text.
 *
 * Copyright (c) 2020, Janssen Project
 */

package io.jans.as.server.service.external;

import io.jans.as.client.RegisterRequest;
import io.jans.as.common.model.registration.Client;
import io.jans.as.model.jwt.Jwt;
import io.jans.as.server.service.external.context.DynamicClientRegistrationContext;
import io.jans.model.SimpleCustomProperty;
import io.jans.model.custom.script.CustomScriptType;
import io.jans.model.custom.script.conf.CustomScriptConfiguration;
import io.jans.model.custom.script.type.client.ClientRegistrationType;
import io.jans.service.custom.script.ExternalScriptService;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;

import javax.ejb.DependsOn;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides factory methods needed to create external dynamic client registration extension
 *
 * @author Yuriy Movchan Date: 01/08/2015
 */
@ApplicationScoped
@DependsOn("appInitializer")
@Named
public class ExternalDynamicClientRegistrationService extends ExternalScriptService {

	private static final long serialVersionUID = 1416361273036208686L;

	public ExternalDynamicClientRegistrationService() {
		super(CustomScriptType.CLIENT_REGISTRATION);
	}

    public boolean executeExternalCreateClientMethod(CustomScriptConfiguration customScriptConfiguration, RegisterRequest registerRequest, Client client, HttpServletRequest httpRequest) {
        try {
            log.trace("Executing python 'createClient' method");
            ClientRegistrationType externalClientRegistrationType = (ClientRegistrationType) customScriptConfiguration.getExternalType();
            Map<String, SimpleCustomProperty> configurationAttributes = customScriptConfiguration.getConfigurationAttributes();
            if (configurationAttributes != null)
                configurationAttributes = new HashMap<>(configurationAttributes);
            else
                configurationAttributes = new HashMap<>();

            final String cert = httpRequest.getHeader("X-ClientCert");

            if (StringUtils.isNotBlank(cert)) {
                SimpleCustomProperty certProperty = new SimpleCustomProperty();
                certProperty.setValue1(cert);
                configurationAttributes.put("certProperty", certProperty);
            }

            return externalClientRegistrationType.createClient(registerRequest, client, configurationAttributes);
        } catch (Exception ex) {
            log.error(ex.getMessage(), ex);
            saveScriptError(customScriptConfiguration.getCustomScript(), ex);
        }
        
        return false;
    }

    public boolean executeExternalCreateClientMethods(RegisterRequest registerRequest, Client client, HttpServletRequest httpRequest) {
        boolean result = true;
        for (CustomScriptConfiguration customScriptConfiguration : this.customScriptConfigurations) {
            if (customScriptConfiguration.getExternalType().getApiVersion() > 1) {
                result &= executeExternalCreateClientMethod(customScriptConfiguration, registerRequest, client, httpRequest);
                if (!result) {
                    return result;
                }
            }
        }

        return result;
    }

	public boolean executeExternalUpdateClientMethod(CustomScriptConfiguration customScriptConfiguration, RegisterRequest registerRequest, Client client) {
		try {
			log.trace("Executing python 'updateClient' method");
			ClientRegistrationType externalClientRegistrationType = (ClientRegistrationType) customScriptConfiguration.getExternalType();
			Map<String, SimpleCustomProperty> configurationAttributes = customScriptConfiguration.getConfigurationAttributes();
			return externalClientRegistrationType.updateClient(registerRequest, client, configurationAttributes);
		} catch (Exception ex) {
			log.error(ex.getMessage(), ex);
            saveScriptError(customScriptConfiguration.getCustomScript(), ex);
		}
		
		return false;
	}

	public boolean executeExternalUpdateClientMethods(RegisterRequest registerRequest, Client client) {
		boolean result = true;
		for (CustomScriptConfiguration customScriptConfiguration : this.customScriptConfigurations) {
			result &= executeExternalUpdateClientMethod(customScriptConfiguration, registerRequest, client);
			if (!result) {
				return result;
			}
		}

		return result;
	}

    public JSONObject getSoftwareStatementJwks(HttpServletRequest httpRequest, JSONObject registerRequest, Jwt softwareStatement) {
        try {
            log.info("Executing python 'getSoftwareStatementJwks' method, script name:" + defaultExternalCustomScript.getName());

            DynamicClientRegistrationContext context = new DynamicClientRegistrationContext(httpRequest, registerRequest, defaultExternalCustomScript);
            context.setSoftwareStatement(softwareStatement);

            ClientRegistrationType externalType = (ClientRegistrationType) defaultExternalCustomScript.getExternalType();
            final String result = externalType.getSoftwareStatementJwks(context);
            log.info("Result of python 'getSoftwareStatementJwks' method: " + result);
            return new JSONObject(result);
        } catch (Exception ex) {
            log.error(ex.getMessage(), ex);
            saveScriptError(defaultExternalCustomScript.getCustomScript(), ex);
            return null;
        }
    }

    public String getSoftwareStatementHmacSecret(HttpServletRequest httpRequest, JSONObject registerRequest, Jwt softwareStatement) {
        try {
            log.trace("Executing python 'getSoftwareStatementHmacSecret' method");

            DynamicClientRegistrationContext context = new DynamicClientRegistrationContext(httpRequest, registerRequest, defaultExternalCustomScript);
            context.setSoftwareStatement(softwareStatement);

            ClientRegistrationType externalType = (ClientRegistrationType) defaultExternalCustomScript.getExternalType();
            final String result = externalType.getSoftwareStatementHmacSecret(context);
            log.trace("Result of python 'getSoftwareStatementHmacSecret' method: " + result);
            return result;
        } catch (Exception ex) {
            log.error(ex.getMessage(), ex);
            saveScriptError(defaultExternalCustomScript.getCustomScript(), ex);
            return "";
        }
    }

    public JSONObject getDcrJwks(HttpServletRequest httpRequest, Jwt dcr) {
        try {
            log.trace("Executing python 'getDcrJwks' method");

            DynamicClientRegistrationContext context = new DynamicClientRegistrationContext(httpRequest, null, defaultExternalCustomScript);
            context.setDcr(dcr);

            ClientRegistrationType externalType = (ClientRegistrationType) defaultExternalCustomScript.getExternalType();
            final String result = externalType.getDcrJwks(context);
            log.trace("Result of python 'getDcrJwks' method: " + result);
            return new JSONObject(result);
        } catch (Exception ex) {
            log.error(ex.getMessage(), ex);
            saveScriptError(defaultExternalCustomScript.getCustomScript(), ex);
            return null;
        }
    }

    public String getDcrHmacSecret(HttpServletRequest httpRequest, Jwt dcr) {
        try {
            log.trace("Executing python 'getDcrHmacSecret' method");

            DynamicClientRegistrationContext context = new DynamicClientRegistrationContext(httpRequest, null, defaultExternalCustomScript);
            context.setDcr(dcr);

            ClientRegistrationType externalType = (ClientRegistrationType) defaultExternalCustomScript.getExternalType();
            final String result = externalType.getDcrHmacSecret(context);
            log.trace("Result of python 'getDcrHmacSecret' method: " + result);
            return result;
        } catch (Exception ex) {
            log.error(ex.getMessage(), ex);
            saveScriptError(defaultExternalCustomScript.getCustomScript(), ex);
            return "";
        }
    }
}
