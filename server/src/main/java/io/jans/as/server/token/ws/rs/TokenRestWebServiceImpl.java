/*
 * Janssen Project software is available under the Apache License (2004). See http://www.apache.org/licenses/ for full text.
 *
 * Copyright (c) 2020, Janssen Project
 */

package io.jans.as.server.token.ws.rs;

import com.google.common.base.Function;
import com.google.common.base.Strings;
import io.jans.as.common.model.common.User;
import io.jans.as.common.model.registration.Client;
import io.jans.as.common.service.AttributeService;
import io.jans.as.model.authorize.CodeVerifier;
import io.jans.as.model.common.ComponentType;
import io.jans.as.model.common.GrantType;
import io.jans.as.model.common.ScopeConstants;
import io.jans.as.model.configuration.AppConfiguration;
import io.jans.as.model.crypto.binding.TokenBindingMessage;
import io.jans.as.model.error.ErrorResponseFactory;
import io.jans.as.model.token.JsonWebResponse;
import io.jans.as.model.token.TokenErrorResponseType;
import io.jans.as.server.audit.ApplicationAuditLogger;
import io.jans.as.server.model.audit.Action;
import io.jans.as.server.model.audit.OAuth2AuditLog;
import io.jans.as.server.model.common.*;
import io.jans.as.server.model.config.Constants;
import io.jans.as.server.model.session.SessionClient;
import io.jans.as.server.model.token.JwrService;
import io.jans.as.server.model.token.TokenParamsValidator;
import io.jans.as.server.security.Identity;
import io.jans.as.server.service.AuthenticationFilterService;
import io.jans.as.server.service.AuthenticationService;
import io.jans.as.server.service.DeviceAuthorizationService;
import io.jans.as.server.service.GrantService;
import io.jans.as.server.service.SessionIdService;
import io.jans.as.server.service.UserService;
import io.jans.as.server.service.ciba.CibaRequestService;
import io.jans.as.server.service.external.ExternalResourceOwnerPasswordCredentialsService;
import io.jans.as.server.service.external.ExternalUpdateTokenService;
import io.jans.as.server.service.external.context.ExternalResourceOwnerPasswordCredentialsContext;
import io.jans.as.server.service.external.context.ExternalUpdateTokenContext;
import io.jans.as.server.uma.service.UmaTokenService;
import io.jans.as.server.util.ServerUtil;
import io.jans.orm.exception.AuthenticationException;
import io.jans.util.OxConstants;
import io.jans.util.StringHelper;
import org.apache.commons.lang.StringUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.SecurityContext;
import java.util.Arrays;
import java.util.Date;

/**
 * Provides interface for token REST web services
 *
 * @author Yuriy Zabrovarnyy
 * @author Javier Rojas Blum
 * @version May 5, 2020
 */
@Path("/")
public class TokenRestWebServiceImpl implements TokenRestWebService {

    @Inject
    private Logger log;

    @Inject
    private Identity identity;

    @Inject
    private ApplicationAuditLogger applicationAuditLogger;

    @Inject
    private ErrorResponseFactory errorResponseFactory;

    @Inject
    private AuthorizationGrantList authorizationGrantList;

    @Inject
    private UserService userService;

    @Inject
    private GrantService grantService;

    @Inject
    private AuthenticationFilterService authenticationFilterService;

    @Inject
    private AuthenticationService authenticationService;

    @Inject
    private AppConfiguration appConfiguration;

    @Inject
    private UmaTokenService umaTokenService;

    @Inject
    private ExternalResourceOwnerPasswordCredentialsService externalResourceOwnerPasswordCredentialsService;

    @Inject
    private AttributeService attributeService;

    @Inject
    private SessionIdService sessionIdService;

    @Inject
    private CibaRequestService cibaRequestService;

    @Inject
    private DeviceAuthorizationService deviceAuthorizationService;
    
    @Inject
    private ExternalUpdateTokenService externalUpdateTokenService;

    @Override
    public Response requestAccessToken(String grantType, String code,
                                       String redirectUri, String username, String password, String scope,
                                       String assertion, String refreshToken,
                                       String clientId, String clientSecret, String codeVerifier,
                                       String ticket, String claimToken, String claimTokenFormat, String pctCode,
                                       String rptCode, String authReqId, String deviceCode,
                                       HttpServletRequest request, HttpServletResponse response, SecurityContext sec) {
        log.debug(
                "Attempting to request access token: grantType = {}, code = {}, redirectUri = {}, username = {}, refreshToken = {}, " +
                        "clientId = {}, ExtraParams = {}, isSecure = {}, codeVerifier = {}, ticket = {}",
                grantType, code, redirectUri, username, refreshToken, clientId, request.getParameterMap(),
                sec.isSecure(), codeVerifier, ticket);

        boolean isUma = StringUtils.isNotBlank(ticket);
        if (isUma) {
            return umaTokenService.requestRpt(grantType, ticket, claimToken, claimTokenFormat, pctCode, rptCode, scope, request, response);
        }

        OAuth2AuditLog oAuth2AuditLog = new OAuth2AuditLog(ServerUtil.getIpAddress(request), Action.TOKEN_REQUEST);
        oAuth2AuditLog.setClientId(clientId);
        oAuth2AuditLog.setUsername(username);
        oAuth2AuditLog.setScope(scope);

        String tokenBindingHeader = request.getHeader("Sec-Token-Binding");

        scope = ServerUtil.urlDecode(scope); // it may be encoded in uma case
        ResponseBuilder builder = Response.ok();

        try {
            log.debug("Starting to validate request parameters");
            if (!TokenParamsValidator.validateParams(grantType, code, redirectUri, username, password,
                    scope, assertion, refreshToken)) {
                log.trace("Failed to validate request parameters");
                return response(error(400, TokenErrorResponseType.INVALID_REQUEST, "Failed to validate request parameters"), oAuth2AuditLog);
            }

            io.jans.as.model.common.GrantType gt = io.jans.as.model.common.GrantType.fromString(grantType);
            log.debug("Grant type: '{}'", gt);

            SessionClient sessionClient = identity.getSessionClient();
            Client client = null;
            if (sessionClient != null) {
                client = sessionClient.getClient();
                log.debug("Get sessionClient: '{}'", sessionClient);
            }

            if (client == null) {
                return response(error(401, TokenErrorResponseType.INVALID_GRANT, "Unable to find client."), oAuth2AuditLog);
            }

            log.debug("Get client from session: '{}'", client.getClientId());
            if (client.isDisabled()) {
                return response(error(Response.Status.FORBIDDEN.getStatusCode(), TokenErrorResponseType.DISABLED_CLIENT, "Client is disabled."), oAuth2AuditLog);
            }

            final Function<JsonWebResponse, Void> idTokenTokingBindingPreprocessing = TokenBindingMessage.createIdTokenTokingBindingPreprocessing(
                    tokenBindingHeader, client.getIdTokenTokenBindingCnf()); // for all except authorization code grant
            final SessionId sessionIdObj = sessionIdService.getSessionId(request);
            final Function<JsonWebResponse, Void> idTokenPreProcessing = JwrService.wrapWithSidFunction(idTokenTokingBindingPreprocessing, sessionIdObj != null ? sessionIdObj.getOutsideSid() : null);


            if (gt == io.jans.as.model.common.GrantType.AUTHORIZATION_CODE) {
                if (!TokenParamsValidator.validateGrantType(gt, client.getGrantTypes(), appConfiguration.getGrantTypesSupported())) {
                    return response(error(400, TokenErrorResponseType.INVALID_GRANT, "Grant types are invalid."), oAuth2AuditLog);
                }

                log.debug("Attempting to find authorizationCodeGrant by clientId: '{}', code: '{}'", client.getClientId(), code);
                final AuthorizationCodeGrant authorizationCodeGrant = authorizationGrantList.getAuthorizationCodeGrant(code);
                log.trace("AuthorizationCodeGrant : '{}'", authorizationCodeGrant);

                if (authorizationCodeGrant == null) {
                    log.debug("AuthorizationCodeGrant is empty by clientId: '{}', code: '{}'", client.getClientId(), code);
                    // if authorization code is not found then code was already used or wrong client provided = remove all grants with this auth code
                    grantService.removeAllByAuthorizationCode(code);
                    return response(error(400, TokenErrorResponseType.INVALID_GRANT, "Unable to find grant object for given code."), oAuth2AuditLog);
                }

                if (!client.getClientId().equals(authorizationCodeGrant.getClientId())) {
                    log.debug("AuthorizationCodeGrant is found but belongs to another client. Grant's clientId: '{}', code: '{}'", authorizationCodeGrant.getClientId(), code);
                    // if authorization code is not found then code was already used or wrong client provided = remove all grants with this auth code
                    grantService.removeAllByAuthorizationCode(code);
                    return response(error(400, TokenErrorResponseType.INVALID_CLIENT, "Client mismatch."), oAuth2AuditLog);
                }

                validatePKCE(authorizationCodeGrant, codeVerifier, oAuth2AuditLog);

                authorizationCodeGrant.setIsCachedWithNoPersistence(false);
                authorizationCodeGrant.save();

                RefreshToken reToken = null;
                if (isRefreshTokenAllowed(client, scope, authorizationCodeGrant)) {
                    reToken = authorizationCodeGrant.createRefreshToken();
                }

                if (scope != null && !scope.isEmpty()) {
                    scope = authorizationCodeGrant.checkScopesPolicy(scope);
                }

                AccessToken accToken = authorizationCodeGrant.createAccessToken(request.getHeader("X-ClientCert"), new ExecutionContext(request, response)); // create token after scopes are checked

                IdToken idToken = null;
                if (authorizationCodeGrant.getScopes().contains("openid")) {
                    String nonce = authorizationCodeGrant.getNonce();
                    boolean includeIdTokenClaims = Boolean.TRUE.equals(
                            appConfiguration.getLegacyIdTokenClaims());
                    final String idTokenTokenBindingCnf = client.getIdTokenTokenBindingCnf();
                    Function<JsonWebResponse, Void> authorizationCodePreProcessing = jsonWebResponse -> {
                        if (StringUtils.isNotBlank(idTokenTokenBindingCnf) && StringUtils.isNotBlank(authorizationCodeGrant.getTokenBindingHash())) {
                            TokenBindingMessage.setCnfClaim(jsonWebResponse, authorizationCodeGrant.getTokenBindingHash(), idTokenTokenBindingCnf);
                        }
                        return null;
                    };
                    
                    ExternalUpdateTokenContext context = new ExternalUpdateTokenContext(request, authorizationCodeGrant, client, appConfiguration, attributeService);
                    Function<JsonWebResponse, Void> postProcessor = externalUpdateTokenService.buildModifyIdTokenProcessor(context);

                    idToken = authorizationCodeGrant.createIdToken(
                            nonce, authorizationCodeGrant.getAuthorizationCode(), accToken, null, null,
                            authorizationCodeGrant, includeIdTokenClaims, JwrService.wrapWithSidFunction(authorizationCodePreProcessing, sessionIdObj != null ? sessionIdObj.getOutsideSid() : null),
                            postProcessor);
                }

                oAuth2AuditLog.updateOAuth2AuditLog(authorizationCodeGrant, true);

                grantService.removeAuthorizationCode(authorizationCodeGrant.getAuthorizationCode().getCode());

                final String entity = getJSonResponse(accToken, accToken.getTokenType(), accToken.getExpiresIn(), reToken, scope, idToken);
                return response(Response.ok().entity(entity), oAuth2AuditLog);
            }

            if (gt == io.jans.as.model.common.GrantType.REFRESH_TOKEN) {
                if (!TokenParamsValidator.validateGrantType(gt, client.getGrantTypes(), appConfiguration.getGrantTypesSupported())) {
                    return response(error(400, TokenErrorResponseType.INVALID_GRANT, "grant_type is not present in client."), oAuth2AuditLog);
                }

                AuthorizationGrant authorizationGrant = authorizationGrantList.getAuthorizationGrantByRefreshToken(client.getClientId(), refreshToken);

                if (authorizationGrant == null) {
                    log.trace("Grant object is not found by refresh token.");
                    return response(error(400, TokenErrorResponseType.INVALID_GRANT, "Unable to find grant object by refresh token or otherwise token type or client does not match."), oAuth2AuditLog);
                }

                final RefreshToken refreshTokenObject = authorizationGrant.getRefreshToken(refreshToken);
                if (refreshTokenObject == null || !refreshTokenObject.isValid()) {
                    log.trace("Invalid refresh token.");
                    return response(error(400, TokenErrorResponseType.INVALID_GRANT, "Unable to find refresh token or otherwise token type or client does not match."), oAuth2AuditLog);
                }

                // The authorization server MAY issue a new refresh token, in which case
                // the client MUST discard the old refresh token and replace it with the new refresh token.
                RefreshToken reToken = null;
                if (!appConfiguration.getSkipRefreshTokenDuringRefreshing()) {
                    if (appConfiguration.getRefreshTokenExtendLifetimeOnRotation()) {
                        reToken = authorizationGrant.createRefreshToken(); // extend lifetime
                    } else {
                        reToken = authorizationGrant.createRefreshToken(refreshTokenObject.getExpirationDate()); // do not extend lifetime
                    }
                }

                if (scope != null && !scope.isEmpty()) {
                    scope = authorizationGrant.checkScopesPolicy(scope);
                }

                AccessToken accToken = authorizationGrant.createAccessToken(request.getHeader("X-ClientCert"), new ExecutionContext(request, response)); // create token after scopes are checked

                IdToken idToken = null;
                if (appConfiguration.getOpenidScopeBackwardCompatibility() && authorizationGrant.getScopes().contains("openid")) {
                    boolean includeIdTokenClaims = Boolean.TRUE.equals(
                            appConfiguration.getLegacyIdTokenClaims());

                    ExternalUpdateTokenContext context = new ExternalUpdateTokenContext(request, authorizationGrant, client, appConfiguration, attributeService);
                    Function<JsonWebResponse, Void> postProcessor = externalUpdateTokenService.buildModifyIdTokenProcessor(context);

                    idToken = authorizationGrant.createIdToken(
                            null, null, accToken, null,
                            null, authorizationGrant, includeIdTokenClaims, idTokenPreProcessing, postProcessor);
                }

                if (reToken != null && refreshToken != null) {
                    grantService.removeByCode(refreshToken); // remove refresh token after access token and id_token is created.
                }

                builder.entity(getJSonResponse(accToken,
                        accToken.getTokenType(),
                        accToken.getExpiresIn(),
                        reToken,
                        scope,
                        idToken));
                oAuth2AuditLog.updateOAuth2AuditLog(authorizationGrant, true);
            } else if (gt == io.jans.as.model.common.GrantType.CLIENT_CREDENTIALS) {
                if (!TokenParamsValidator.validateGrantType(gt, client.getGrantTypes(), appConfiguration.getGrantTypesSupported())) {
                    return response(error(400, TokenErrorResponseType.INVALID_GRANT, "grant_type is not present in client."), oAuth2AuditLog);
                }

                ClientCredentialsGrant clientCredentialsGrant = authorizationGrantList.createClientCredentialsGrant(new User(), client); // TODO: fix the user arg

                if (scope != null && !scope.isEmpty()) {
                    scope = clientCredentialsGrant.checkScopesPolicy(scope);
                }

                AccessToken accessToken = clientCredentialsGrant.createAccessToken(request.getHeader("X-ClientCert"), new ExecutionContext(request, response)); // create token after scopes are checked

                IdToken idToken = null;
                if (appConfiguration.getOpenidScopeBackwardCompatibility() && clientCredentialsGrant.getScopes().contains("openid")) {
                    boolean includeIdTokenClaims = Boolean.TRUE.equals(
                            appConfiguration.getLegacyIdTokenClaims());

                    ExternalUpdateTokenContext context = new ExternalUpdateTokenContext(request, clientCredentialsGrant, client, appConfiguration, attributeService);
                    Function<JsonWebResponse, Void> postProcessor = externalUpdateTokenService.buildModifyIdTokenProcessor(context);

                    idToken = clientCredentialsGrant.createIdToken(
                            null, null, null, null,
                            null, clientCredentialsGrant, includeIdTokenClaims, idTokenPreProcessing, postProcessor);
                }

                oAuth2AuditLog.updateOAuth2AuditLog(clientCredentialsGrant, true);
                builder.entity(getJSonResponse(accessToken,
                        accessToken.getTokenType(),
                        accessToken.getExpiresIn(),
                        null,
                        scope,
                        idToken));
            } else if (gt == io.jans.as.model.common.GrantType.RESOURCE_OWNER_PASSWORD_CREDENTIALS) {
                if (!TokenParamsValidator.validateGrantType(gt, client.getGrantTypes(), appConfiguration.getGrantTypesSupported())) {
                    return response(error(400, TokenErrorResponseType.INVALID_GRANT, "grant_type is not present in client."), oAuth2AuditLog);
                }

                boolean authenticated = false;
                User user = null;
                if (authenticationFilterService.isEnabled()) {
                    String userDn = authenticationFilterService.processAuthenticationFilters(request.getParameterMap());
                    if (StringHelper.isNotEmpty(userDn)) {
                        user = userService.getUserByDn(userDn);
                        authenticated = true;
                    }
                }


                if (!authenticated) {
                    if (externalResourceOwnerPasswordCredentialsService.isEnabled()) {
                        final ExternalResourceOwnerPasswordCredentialsContext context = new ExternalResourceOwnerPasswordCredentialsContext(request, response, appConfiguration, attributeService, userService);
                        context.setUser(user);
                        if (externalResourceOwnerPasswordCredentialsService.executeExternalAuthenticate(context)) {
                            log.trace("RO PC - User is authenticated successfully by external script.");
                            user = context.getUser();
                        }
                    } else {
                        try {
                            authenticated = authenticationService.authenticate(username, password);
                            if (authenticated) {
                                user = authenticationService.getAuthenticatedUser();
                            }
                        } catch (AuthenticationException ex) {
                            log.trace("Failed to authenticate user ", new RuntimeException("User name or password is invalid"));
                        }
                    }
                }

                if (user != null) {
                    ResourceOwnerPasswordCredentialsGrant resourceOwnerPasswordCredentialsGrant = authorizationGrantList.createResourceOwnerPasswordCredentialsGrant(user, client);
                    SessionId sessionId = identity.getSessionId();
                    if (sessionId != null) {
                        resourceOwnerPasswordCredentialsGrant.setAcrValues(OxConstants.SCRIPT_TYPE_INTERNAL_RESERVED_NAME);
                        resourceOwnerPasswordCredentialsGrant.setSessionDn(sessionId.getDn());
                        resourceOwnerPasswordCredentialsGrant.save(); // call save after object modification!!!

                        sessionId.getSessionAttributes().put(Constants.AUTHORIZED_GRANT, gt.getValue());
                        boolean updateResult = sessionIdService.updateSessionId(sessionId, false, true, true);
                        if (!updateResult) {
                            log.debug("Failed to update session entry: '{}'", sessionId.getId());
                        }
                    }


                    RefreshToken reToken = null;
                    if (isRefreshTokenAllowed(client, scope, resourceOwnerPasswordCredentialsGrant)) {
                        reToken = resourceOwnerPasswordCredentialsGrant.createRefreshToken();
                    }

                    if (scope != null && !scope.isEmpty()) {
                        scope = resourceOwnerPasswordCredentialsGrant.checkScopesPolicy(scope);
                    }

                    AccessToken accessToken = resourceOwnerPasswordCredentialsGrant.createAccessToken(request.getHeader("X-ClientCert"), new ExecutionContext(request, response)); // create token after scopes are checked

                    IdToken idToken = null;
                    if (appConfiguration.getOpenidScopeBackwardCompatibility() && resourceOwnerPasswordCredentialsGrant.getScopes().contains("openid")) {
                        boolean includeIdTokenClaims = Boolean.TRUE.equals(
                                appConfiguration.getLegacyIdTokenClaims());

                        ExternalUpdateTokenContext context = new ExternalUpdateTokenContext(request, resourceOwnerPasswordCredentialsGrant, client, appConfiguration, attributeService);
                        Function<JsonWebResponse, Void> postProcessor = externalUpdateTokenService.buildModifyIdTokenProcessor(context);

                        idToken = resourceOwnerPasswordCredentialsGrant.createIdToken(
                                null, null, null, null,
                                null, resourceOwnerPasswordCredentialsGrant, includeIdTokenClaims, idTokenPreProcessing, postProcessor);
                    }

                    oAuth2AuditLog.updateOAuth2AuditLog(resourceOwnerPasswordCredentialsGrant, true);
                    builder.entity(getJSonResponse(accessToken,
                            accessToken.getTokenType(),
                            accessToken.getExpiresIn(),
                            reToken,
                            scope,
                            idToken));
                } else {
                    log.debug("Invalid user", new RuntimeException("User is empty"));
                    builder = error(401, TokenErrorResponseType.INVALID_CLIENT, "Invalid user.");
                }
            } else if (gt == io.jans.as.model.common.GrantType.CIBA) {
                errorResponseFactory.validateComponentEnabled(ComponentType.CIBA);

                if (!TokenParamsValidator.validateGrantType(gt, client.getGrantTypes(), appConfiguration.getGrantTypesSupported())) {
                    return response(error(400, TokenErrorResponseType.INVALID_GRANT, "Grant types are invalid."), oAuth2AuditLog);
                }

                log.debug("Attempting to find authorizationGrant by authReqId: '{}'", authReqId);
                final CIBAGrant cibaGrant = authorizationGrantList.getCIBAGrant(authReqId);

                log.trace("AuthorizationGrant : '{}'", cibaGrant);

                if (cibaGrant != null) {
                    if (!cibaGrant.getClientId().equals(client.getClientId())) {
                        builder = error(400, TokenErrorResponseType.INVALID_GRANT, "The client is not authorized.");
                        return response(builder, oAuth2AuditLog);
                    }
                    if (cibaGrant.getClient().getBackchannelTokenDeliveryMode() == io.jans.as.model.common.BackchannelTokenDeliveryMode.PING ||
                            cibaGrant.getClient().getBackchannelTokenDeliveryMode() == io.jans.as.model.common.BackchannelTokenDeliveryMode.POLL) {
                        if (!cibaGrant.isTokensDelivered()) {
                            RefreshToken refToken = cibaGrant.createRefreshToken();
                            AccessToken accessToken = cibaGrant.createAccessToken(request.getHeader("X-ClientCert"), new ExecutionContext(request, response));

                            ExternalUpdateTokenContext context = new ExternalUpdateTokenContext(request, cibaGrant, client, appConfiguration, attributeService);
                            Function<JsonWebResponse, Void> postProcessor = externalUpdateTokenService.buildModifyIdTokenProcessor(context);

                            IdToken idToken = cibaGrant.createIdToken(
                                    null, null, accessToken, refToken,
                                    null, cibaGrant, false, null, postProcessor);

                            cibaGrant.setTokensDelivered(true);
                            cibaGrant.save();

                            RefreshToken reToken = null;
                            if (isRefreshTokenAllowed(client, scope, cibaGrant)) {
                                reToken = refToken;
                            }

                            if (scope != null && !scope.isEmpty()) {
                                scope = cibaGrant.checkScopesPolicy(scope);
                            }

                            builder.entity(getJSonResponse(accessToken,
                                    accessToken.getTokenType(),
                                    accessToken.getExpiresIn(),
                                    reToken,
                                    scope,
                                    idToken));

                            oAuth2AuditLog.updateOAuth2AuditLog(cibaGrant, true);
                        } else {
                            builder = error(400, TokenErrorResponseType.INVALID_GRANT, "AuthReqId is no longer available.");
                        }
                    } else {
                        log.debug("Client is not using Poll flow authReqId: '{}'", authReqId);
                        builder = error(400, TokenErrorResponseType.UNAUTHORIZED_CLIENT, "The client is not authorized as it is configured in Push Mode");
                    }
                } else {
                    final CibaRequestCacheControl cibaRequest = cibaRequestService.getCibaRequest(authReqId);
                    log.trace("Ciba request : '{}'", cibaRequest);
                    if (cibaRequest != null) {
                        if (!cibaRequest.getClient().getClientId().equals(client.getClientId())) {
                            builder = error(400, TokenErrorResponseType.INVALID_GRANT, "The client is not authorized.");
                            return response(builder, oAuth2AuditLog);
                        }
                        long currentTime = new Date().getTime();
                        Long lastAccess = cibaRequest.getLastAccessControl();
                        if (lastAccess == null) {
                            lastAccess = currentTime;
                        }
                        cibaRequest.setLastAccessControl(currentTime);
                        cibaRequestService.update(cibaRequest);

                        if (cibaRequest.getStatus() == CibaRequestStatus.PENDING) {
                            int intervalSeconds = appConfiguration.getBackchannelAuthenticationResponseInterval();
                            long timeFromLastAccess = currentTime - lastAccess;

                            if (timeFromLastAccess > intervalSeconds * 1000) {
                                log.debug("Access hasn't been granted yet for authReqId: '{}'", authReqId);
                                builder = error(400, TokenErrorResponseType.AUTHORIZATION_PENDING, "User hasn't answered yet");
                            } else {
                                log.debug("Slow down protection authReqId: '{}'", authReqId);
                                builder = error(400, TokenErrorResponseType.SLOW_DOWN, "Client is asking too fast the token.");
                            }
                        } else if (cibaRequest.getStatus() == CibaRequestStatus.DENIED) {
                            log.debug("The end-user denied the authorization request for authReqId: '{}'", authReqId);
                            builder = error(400, TokenErrorResponseType.ACCESS_DENIED, "The end-user denied the authorization request.");
                        } else if (cibaRequest.getStatus() == CibaRequestStatus.EXPIRED) {
                            log.debug("The authentication request has expired for authReqId: '{}'", authReqId);
                            builder = error(400, TokenErrorResponseType.EXPIRED_TOKEN, "The authentication request has expired");
                        }
                    } else {
                        log.debug("AuthorizationGrant is empty by authReqId: '{}'", authReqId);
                        builder = error(400, TokenErrorResponseType.EXPIRED_TOKEN, "Unable to find grant object for given auth_req_id.");
                    }
                }
            } else if (gt == io.jans.as.model.common.GrantType.DEVICE_CODE) {
                return processDeviceCodeGrantType(gt, client, deviceCode, scope, request, response, oAuth2AuditLog);
            }
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            builder = Response.status(500);
            log.error(e.getMessage(), e);
        }

        return response(builder, oAuth2AuditLog);
    }

    /**
     * Processes token request for device code grant type.
     * @param grantType Grant type used, should be device code.
     * @param client Client in process.
     * @param deviceCode Device code generated in device authn request.
     * @param scope Scope registered in device authn request.
     * @param request HttpServletRequest
     * @param response HttpServletResponse
     * @param oAuth2AuditLog OAuth2AuditLog
     */
    private Response processDeviceCodeGrantType(final io.jans.as.model.common.GrantType grantType, final Client client, final String deviceCode,
                                                String scope, final HttpServletRequest request,
                                                final HttpServletResponse response, final OAuth2AuditLog oAuth2AuditLog) {
        if (!TokenParamsValidator.validateGrantType(grantType, client.getGrantTypes(), appConfiguration.getGrantTypesSupported())) {
            return response(error(400, TokenErrorResponseType.INVALID_GRANT, "Grant types are invalid."), oAuth2AuditLog);
        }

        log.debug("Attempting to find authorizationGrant by deviceCode: '{}'", deviceCode);
        final DeviceCodeGrant deviceCodeGrant = authorizationGrantList.getDeviceCodeGrant(deviceCode);

        log.trace("DeviceCodeGrant : '{}'", deviceCodeGrant);

        if (deviceCodeGrant != null) {
            if (!deviceCodeGrant.getClientId().equals(client.getClientId())) {
                throw new WebApplicationException(response(error(400, TokenErrorResponseType.INVALID_GRANT, "The client is not authorized."), oAuth2AuditLog));
            }
            RefreshToken refToken = deviceCodeGrant.createRefreshToken();
            AccessToken accessToken = deviceCodeGrant.createAccessToken(request.getHeader("X-ClientCert"), new ExecutionContext(request, response));

            ExternalUpdateTokenContext context = new ExternalUpdateTokenContext(request, deviceCodeGrant, client, appConfiguration, attributeService);
            Function<JsonWebResponse, Void> postProcessor = externalUpdateTokenService.buildModifyIdTokenProcessor(context);

            IdToken idToken = deviceCodeGrant.createIdToken(
                    null, null, accessToken, refToken,
                    null, deviceCodeGrant, false, null, postProcessor);

            RefreshToken reToken = null;
            if (isRefreshTokenAllowed(client, scope, deviceCodeGrant)) {
                reToken = refToken;
            }

            if (scope != null && !scope.isEmpty()) {
                scope = deviceCodeGrant.checkScopesPolicy(scope);
            }
            log.info("Device authorization in token endpoint processed and return to the client, device_code: {}", deviceCodeGrant.getDeviceCode());

            oAuth2AuditLog.updateOAuth2AuditLog(deviceCodeGrant, true);

            grantService.removeByCode(deviceCodeGrant.getDeviceCode());

            return Response.ok().entity(getJSonResponse(accessToken, accessToken.getTokenType(),
                    accessToken.getExpiresIn(), reToken, scope, idToken)).build();
        } else {
            final DeviceAuthorizationCacheControl cacheData = deviceAuthorizationService.getDeviceAuthzByDeviceCode(deviceCode);
            log.trace("DeviceAuthorizationCacheControl data : '{}'", cacheData);
            if (cacheData == null) {
                log.debug("The authentication request has expired for deviceCode: '{}'", deviceCode);
                throw new WebApplicationException(response(error(400, TokenErrorResponseType.EXPIRED_TOKEN, "The authentication request has expired."), oAuth2AuditLog));
            }
            if (!cacheData.getClient().getClientId().equals(client.getClientId())) {
                throw new WebApplicationException(response(error(400, TokenErrorResponseType.INVALID_GRANT, "The client is not authorized."), oAuth2AuditLog));
            }
            long currentTime = new Date().getTime();
            Long lastAccess = cacheData.getLastAccessControl();
            if (lastAccess == null) {
                lastAccess = currentTime;
            }
            cacheData.setLastAccessControl(currentTime);
            deviceAuthorizationService.saveInCache(cacheData, true, true);

            if (cacheData.getStatus() == DeviceAuthorizationStatus.PENDING) {
                int intervalSeconds = appConfiguration.getBackchannelAuthenticationResponseInterval();
                long timeFromLastAccess = currentTime - lastAccess;

                if (timeFromLastAccess > intervalSeconds * 1000) {
                    log.debug("Access hasn't been granted yet for deviceCode: '{}'", deviceCode);
                    throw new WebApplicationException(response(error(400, TokenErrorResponseType.AUTHORIZATION_PENDING, "User hasn't answered yet"), oAuth2AuditLog));
                } else {
                    log.debug("Slow down protection deviceCode: '{}'", deviceCode);
                    throw new WebApplicationException(response(error(400, TokenErrorResponseType.SLOW_DOWN, "Client is asking too fast the token."), oAuth2AuditLog));
                }
            }
            if (cacheData.getStatus() == DeviceAuthorizationStatus.DENIED) {
                log.debug("The end-user denied the authorization request for deviceCode: '{}'", deviceCode);
                throw new WebApplicationException(response(error(400, TokenErrorResponseType.ACCESS_DENIED, "The end-user denied the authorization request."), oAuth2AuditLog));
            }
            log.debug("The authentication request has expired for deviceCode: '{}'", deviceCode);
            throw new WebApplicationException(response(error(400, TokenErrorResponseType.EXPIRED_TOKEN, "The authentication request has expired"), oAuth2AuditLog));
        }
    }

    private boolean isRefreshTokenAllowed(Client client, String requestedScope, AbstractAuthorizationGrant grant) {
        if (appConfiguration.getForceOfflineAccessScopeToEnableRefreshToken() && !grant.getScopes().contains(ScopeConstants.OFFLINE_ACCESS) && !Strings.nullToEmpty(requestedScope).contains(ScopeConstants.OFFLINE_ACCESS)) {
            return false;
        }
        return Arrays.asList(client.getGrantTypes()).contains(GrantType.REFRESH_TOKEN);
    }

    private void validatePKCE(AuthorizationCodeGrant grant, String codeVerifier, OAuth2AuditLog oAuth2AuditLog) {
        log.trace("PKCE validation, code_verifier: {}, code_challenge: {}, method: {}",
                codeVerifier, grant.getCodeChallenge(), grant.getCodeChallengeMethod());

        if (Strings.isNullOrEmpty(grant.getCodeChallenge()) && Strings.isNullOrEmpty(codeVerifier)) {
            return; // if no code challenge then it's valid, no PKCE check
        }

        if (!CodeVerifier.matched(grant.getCodeChallenge(), grant.getCodeChallengeMethod(), codeVerifier)) {
            log.error("PKCE check fails. Code challenge does not match to request code verifier, " +
                    "grantId:" + grant.getGrantId() + ", codeVerifier: " + codeVerifier);
            throw new WebApplicationException(response(error(401, TokenErrorResponseType.INVALID_GRANT, "PKCE check fails. Code challenge does not match to request code verifier."), oAuth2AuditLog));
        }
    }

    private Response response(ResponseBuilder builder, OAuth2AuditLog oAuth2AuditLog) {
        builder.cacheControl(ServerUtil.cacheControl(true, false));
        builder.header("Pragma", "no-cache");

        applicationAuditLogger.sendMessage(oAuth2AuditLog);

        return builder.build();
    }

    private ResponseBuilder error(int p_status, TokenErrorResponseType p_type, String reason) {
        return Response.status(p_status).type(MediaType.APPLICATION_JSON_TYPE).entity(errorResponseFactory.errorAsJson(p_type, reason));
    }

    /**
     * Builds a JSon String with the structure for token issues.
     */
    public String getJSonResponse(AccessToken accessToken, io.jans.as.model.common.TokenType tokenType,
                                  Integer expiresIn, RefreshToken refreshToken, String scope,
                                  IdToken idToken) {
        JSONObject jsonObj = new JSONObject();
        try {
            jsonObj.put("access_token", accessToken.getCode()); // Required
            jsonObj.put("token_type", tokenType.toString()); // Required
            if (expiresIn != null) { // Optional
                jsonObj.put("expires_in", expiresIn);
            }
            if (refreshToken != null) { // Optional
                jsonObj.put("refresh_token", refreshToken.getCode());
            }
            if (scope != null) { // Optional
                jsonObj.put("scope", scope);
            }
            if (idToken != null) {
                jsonObj.put("id_token", idToken.getCode());
            }
        } catch (JSONException e) {
            log.error(e.getMessage(), e);
        }

        return jsonObj.toString();
    }
}
