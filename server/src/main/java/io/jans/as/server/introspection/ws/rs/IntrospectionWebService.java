/*
 * Janssen Project software is available under the Apache License (2004). See http://www.apache.org/licenses/ for full text.
 *
 * Copyright (c) 2020, Janssen Project
 */

package io.jans.as.server.introspection.ws.rs;

import com.google.common.collect.Lists;
import io.jans.as.common.claims.Audience;
import io.jans.as.common.service.AttributeService;
import io.jans.as.model.authorize.AuthorizeErrorResponseType;
import io.jans.as.model.common.ComponentType;
import io.jans.as.model.common.IntrospectionResponse;
import io.jans.as.model.config.WebKeysConfiguration;
import io.jans.as.model.configuration.AppConfiguration;
import io.jans.as.model.error.ErrorResponseFactory;
import io.jans.as.model.jwt.Jwt;
import io.jans.as.model.uma.UmaScopeType;
import io.jans.as.model.util.Util;
import io.jans.as.server.model.common.AbstractToken;
import io.jans.as.server.model.common.AccessToken;
import io.jans.as.server.model.common.AuthorizationGrant;
import io.jans.as.server.model.common.AuthorizationGrantList;
import io.jans.as.server.model.token.JwtSigner;
import io.jans.as.server.service.ClientService;
import io.jans.as.server.service.external.ExternalIntrospectionService;
import io.jans.as.server.service.external.context.ExternalIntrospectionContext;
import io.jans.as.server.service.token.TokenService;
import io.jans.as.server.util.ServerUtil;
import io.jans.util.Pair;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Iterator;

/**
 * @author Yuriy Zabrovarnyy
 * @version June 30, 2018
 */
@Path("/introspection")
public class IntrospectionWebService {

    private static final Pair<AuthorizationGrant, Boolean> EMPTY = new Pair<>(null, false);

    @Inject
    private Logger log;
    @Inject
    private AppConfiguration appConfiguration;
    @Inject
    private TokenService tokenService;
    @Inject
    private ErrorResponseFactory errorResponseFactory;
    @Inject
    private AuthorizationGrantList authorizationGrantList;
    @Inject
    private ClientService clientService;
    @Inject
    private ExternalIntrospectionService externalIntrospectionService;
    @Inject
    private AttributeService attributeService;
    @Inject
    private WebKeysConfiguration webKeysConfiguration;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response introspectGet(@HeaderParam("Authorization") String p_authorization,
                                  @QueryParam("token") String p_token,
                                  @QueryParam("token_type_hint") String tokenTypeHint,
                                  @QueryParam("response_as_jwt") String responseAsJwt,
                                  @Context HttpServletRequest httpRequest,
                                  @Context HttpServletResponse httpResponse
    ) {
        return introspect(p_authorization, p_token, tokenTypeHint, responseAsJwt, httpRequest, httpResponse);
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response introspectPost(@HeaderParam("Authorization") String p_authorization,
                                   @FormParam("token") String p_token,
                                   @FormParam("token_type_hint") String tokenTypeHint,
                                   @FormParam("response_as_jwt") String responseAsJwt,
                                   @Context HttpServletRequest httpRequest,
                                   @Context HttpServletResponse httpResponse) {
        return introspect(p_authorization, p_token, tokenTypeHint, responseAsJwt, httpRequest, httpResponse);
    }

    private Response introspect(String p_authorization, String p_token, String tokenTypeHint, String responseAsJwt, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        try {
            log.trace("Introspect token, authorization: {}, token to introsppect: {}, tokenTypeHint: {}", p_authorization, p_token, tokenTypeHint);
            errorResponseFactory.validateComponentEnabled(ComponentType.INTROSPECTION);
            if (StringUtils.isBlank(p_authorization) || StringUtils.isBlank(p_token)) {
                log.trace("Bad request: Authorization header or token is blank.");
                return Response.status(Response.Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON_TYPE).entity(errorResponseFactory.errorAsJson(AuthorizeErrorResponseType.INVALID_REQUEST, "")).build();
            }

            final Pair<AuthorizationGrant, Boolean> pair = getAuthorizationGrant(p_authorization, p_token);
            final AuthorizationGrant authorizationGrant = pair.getFirst();
            if (authorizationGrant == null) {
                log.error("Authorization grant is null.");
                return Response.status(Response.Status.UNAUTHORIZED).type(MediaType.APPLICATION_JSON_TYPE).entity(errorResponseFactory.errorAsJson(AuthorizeErrorResponseType.ACCESS_DENIED, "Authorization grant is null.")).build();
            }

            final AbstractToken authorizationAccessToken = authorizationGrant.getAccessToken(tokenService.getToken(p_authorization));

            if ((authorizationAccessToken == null || !authorizationAccessToken.isValid()) && !pair.getSecond()) {
                log.error("Access token is not valid. Valid: " + (authorizationAccessToken != null && authorizationAccessToken.isValid()) + ", basicClientAuthentication: " + pair.getSecond());
                return Response.status(Response.Status.UNAUTHORIZED).type(MediaType.APPLICATION_JSON_TYPE).entity(errorResponseFactory.errorAsJson(AuthorizeErrorResponseType.ACCESS_DENIED, "Access token is not valid")).build();
            }

            if (ServerUtil.isTrue(appConfiguration.getIntrospectionAccessTokenMustHaveUmaProtectionScope()) &&
                    !authorizationGrant.getScopesAsString().contains(UmaScopeType.PROTECTION.getValue())) { // #562 - make uma_protection optional
                final String reason = "access_token used to access introspection endpoint does not have uma_protection scope, however in oxauth configuration `checkUmaProtectionScopePresenceDuringIntrospection` is true";
                log.trace(reason);
                return Response.status(Response.Status.UNAUTHORIZED).entity(errorResponseFactory.errorAsJson(AuthorizeErrorResponseType.ACCESS_DENIED, reason)).type(MediaType.APPLICATION_JSON_TYPE).build();
            }

            final io.jans.as.model.common.IntrospectionResponse response = new io.jans.as.model.common.IntrospectionResponse(false);

            final AuthorizationGrant grantOfIntrospectionToken = authorizationGrantList.getAuthorizationGrantByAccessToken(p_token);

            AbstractToken tokenToIntrospect = null;
            if (grantOfIntrospectionToken != null) {
                tokenToIntrospect = grantOfIntrospectionToken.getAccessToken(p_token);

                response.setActive(tokenToIntrospect.isValid());
                response.setExpiresAt(ServerUtil.dateToSeconds(tokenToIntrospect.getExpirationDate()));
                response.setIssuedAt(ServerUtil.dateToSeconds(tokenToIntrospect.getCreationDate()));
                response.setAcrValues(grantOfIntrospectionToken.getAcrValues());
                response.setScope(grantOfIntrospectionToken.getScopes() != null ? grantOfIntrospectionToken.getScopes() : Lists.newArrayList()); // #433
                response.setClientId(grantOfIntrospectionToken.getClientId());
                response.setSub(grantOfIntrospectionToken.getSub());
                response.setUsername(grantOfIntrospectionToken.getUserId());
                response.setIssuer(appConfiguration.getIssuer());
                response.setAudience(grantOfIntrospectionToken.getClientId());

                if (tokenToIntrospect instanceof AccessToken) {
                    AccessToken accessToken = (AccessToken) tokenToIntrospect;
                    response.setTokenType(accessToken.getTokenType() != null ? accessToken.getTokenType().getName() : io.jans.as.model.common.TokenType.BEARER.getName());
                }
            } else {
                log.debug("Failed to find grant for access_token: " + p_token + ". Return 200 with active=false.");
            }
            JSONObject responseAsJsonObject = createResponseAsJsonObject(response, tokenToIntrospect);

            ExternalIntrospectionContext context = new ExternalIntrospectionContext(authorizationGrant, httpRequest, httpResponse, appConfiguration, attributeService);
            context.setGrantOfIntrospectionToken(grantOfIntrospectionToken);
            if (externalIntrospectionService.executeExternalModifyResponse(responseAsJsonObject, context)) {
                log.trace("Successfully run extenal introspection scripts.");
            } else {
                responseAsJsonObject = createResponseAsJsonObject(response, tokenToIntrospect);
                log.trace("Canceled changes made by external introspection script since method returned `false`.");
            }

            // Make scopes conform as required by spec, see #1499
            if (response.getScope()!= null && !appConfiguration.getIntrospectionResponseScopesBackwardCompatibility()) {
            	String scopes = StringUtils.join(response.getScope().toArray(), " ");
            	responseAsJsonObject.put("scope", scopes);
            }
            if (Boolean.TRUE.toString().equalsIgnoreCase(responseAsJwt)) {
                return Response.status(Response.Status.OK).entity(createResponseAsJwt(responseAsJsonObject, authorizationGrant)).build();
            }

            return Response.status(Response.Status.OK).entity(responseAsJsonObject.toString()).type(MediaType.APPLICATION_JSON_TYPE).build();

        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).type(MediaType.APPLICATION_JSON_TYPE).build();
        }
    }

    private String createResponseAsJwt(JSONObject response, AuthorizationGrant grant) throws Exception {
        final JwtSigner jwtSigner = JwtSigner.newJwtSigner(appConfiguration, webKeysConfiguration, grant.getClient());
        final Jwt jwt = jwtSigner.newJwt();
        Audience.setAudience(jwt.getClaims(), grant.getClient());

        Iterator<String> keysIter = response.keys();
        while (keysIter.hasNext()) {
            String key = keysIter.next();
            Object value = response.opt(key);
            if (value != null) {
                try {
                    jwt.getClaims().setClaimObject(key, value, false);
                } catch (Exception e) {
                    log.error("Failed to put claims into jwt. Key: " + key + ", response: " + response.toString(), e);
                }
            }
        }

        return jwtSigner.sign().toString();
    }

    private static JSONObject createResponseAsJsonObject(IntrospectionResponse response, AbstractToken tokenToIntrospect) throws JSONException, IOException {
        final JSONObject result = new JSONObject(ServerUtil.asJson(response));
        if (tokenToIntrospect != null && StringUtils.isNotBlank(tokenToIntrospect.getX5ts256())) {
            final JSONObject cnf = new JSONObject();
            cnf.put("x5t#S256", tokenToIntrospect.getX5ts256());
            result.put("cnf", cnf);
        }

        return result;
    }

    /**
     * @return we return pair of authorization grant or otherwise true - if it's basic client authentication or false if it is not
     * @throws UnsupportedEncodingException when encoding is not supported
     */
    private Pair<AuthorizationGrant, Boolean> getAuthorizationGrant(String authorization, String accessToken) throws UnsupportedEncodingException {
        AuthorizationGrant grant = tokenService.getBearerAuthorizationGrant(authorization);
        if (grant != null) {
            final String authorizationAccessToken = tokenService.getBearerToken(authorization);
            final AbstractToken accessTokenObject = grant.getAccessToken(authorizationAccessToken);
            if (accessTokenObject != null && accessTokenObject.isValid()) {
                return new Pair<>(grant, false);
            } else {
                log.error("Access token is not valid: " + authorizationAccessToken);
                return EMPTY;
            }
        }

        grant = tokenService.getBasicAuthorizationGrant(authorization);
        if (grant != null) {
            return new Pair<>(grant, false);
        }
        if (tokenService.isBasicAuthToken(authorization)) {
            
            String encodedCredentials = tokenService.getBasicToken(authorization);

            String token = new String(Base64.decodeBase64(encodedCredentials), Util.UTF8_STRING_ENCODING);

            int delim = token.indexOf(":");

            if (delim != -1) {
                String clientId = URLDecoder.decode(token.substring(0, delim), Util.UTF8_STRING_ENCODING);
                String password = URLDecoder.decode(token.substring(delim + 1), Util.UTF8_STRING_ENCODING);
                if (clientService.authenticate(clientId, password)) {
                    grant = authorizationGrantList.getAuthorizationGrantByAccessToken(accessToken);
                    if (grant != null && !grant.getClientId().equals(clientId)) {
                        log.trace("Failed to match grant object clientId and client id provided during authentication.");
                        return EMPTY;
                    }
                    return new Pair<>(grant, true);
                } else {
                    log.trace("Failed to perform basic authentication for client: " + clientId);
                }
            }
        }
        return EMPTY;
    }

}
