/*
 * Janssen Project software is available under the Apache License (2004). See http://www.apache.org/licenses/ for full text.
 *
 * Copyright (c) 2020, Janssen Project
 */

package io.jans.as.server.authorize.ws.rs;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.jans.as.common.model.common.User;
import io.jans.as.common.model.registration.Client;
import io.jans.as.common.util.RedirectUri;
import io.jans.as.model.authorize.AuthorizeErrorResponseType;
import io.jans.as.model.authorize.AuthorizeResponseParam;
import io.jans.as.model.common.GrantType;
import io.jans.as.model.common.Prompt;
import io.jans.as.model.common.ResponseType;
import io.jans.as.model.common.ScopeConstants;
import io.jans.as.model.configuration.AppConfiguration;
import io.jans.as.model.crypto.AbstractCryptoProvider;
import io.jans.as.model.crypto.binding.TokenBindingMessage;
import io.jans.as.model.error.ErrorResponseFactory;
import io.jans.as.model.jwt.JwtClaimName;
import io.jans.as.model.token.JsonWebResponse;
import io.jans.as.model.util.Util;
import io.jans.as.server.audit.ApplicationAuditLogger;
import io.jans.as.server.ciba.CIBAPingCallbackService;
import io.jans.as.server.ciba.CIBAPushTokenDeliveryService;
import io.jans.as.server.model.audit.Action;
import io.jans.as.server.model.audit.OAuth2AuditLog;
import io.jans.as.server.model.authorize.*;
import io.jans.as.server.model.common.*;
import io.jans.as.server.model.config.ConfigurationFactory;
import io.jans.as.server.model.config.Constants;
import io.jans.as.server.model.exception.AcrChangedException;
import io.jans.as.server.model.exception.InvalidSessionStateException;
import io.jans.as.server.model.ldap.ClientAuthorization;
import io.jans.as.server.model.token.JwrService;
import io.jans.as.server.security.Identity;
import io.jans.as.server.service.*;
import io.jans.as.server.service.ciba.CibaRequestService;
import io.jans.as.server.service.external.ExternalPostAuthnService;
import io.jans.as.server.service.external.ExternalUpdateTokenService;
import io.jans.as.server.service.external.context.ExternalPostAuthnContext;
import io.jans.as.server.service.external.context.ExternalUpdateTokenContext;
import io.jans.as.server.service.external.session.SessionEvent;
import io.jans.as.server.service.external.session.SessionEventType;
import io.jans.as.server.util.QueryStringDecoder;
import io.jans.as.server.util.RedirectUtil;
import io.jans.as.server.util.ServerUtil;
import io.jans.orm.exception.EntryPersistenceException;
import io.jans.util.StringHelper;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.SecurityContext;
import java.net.URI;
import java.util.*;
import java.util.Map.Entry;

import static io.jans.as.model.util.StringUtils.implode;

/**
 * Implementation for request authorization through REST web services.
 *
 * @author Javier Rojas Blum
 * @version May 9, 2020
 */
@Path("/")
public class AuthorizeRestWebServiceImpl implements AuthorizeRestWebService {

    @Inject
    private Logger log;

    @Inject
    private ApplicationAuditLogger applicationAuditLogger;

    @Inject
    private ErrorResponseFactory errorResponseFactory;

    @Inject
    private AuthorizationGrantList authorizationGrantList;

    @Inject
    private ClientService clientService;

    @Inject
    private UserService userService;

    @Inject
    private Identity identity;

    @Inject
    private AuthenticationFilterService authenticationFilterService;

    @Inject
    private SessionIdService sessionIdService;

    @Inject CookieService cookieService;

    @Inject
    private ScopeChecker scopeChecker;

    @Inject
    private ClientAuthorizationsService clientAuthorizationsService;

    @Inject
    private RequestParameterService requestParameterService;

    @Inject
    private AppConfiguration appConfiguration;

    @Inject
    private ConfigurationFactory сonfigurationFactory;

    @Inject
    private AbstractCryptoProvider cryptoProvider;

    @Inject
    private AuthorizeRestWebServiceValidator authorizeRestWebServiceValidator;

    @Inject
    private CIBAPushTokenDeliveryService cibaPushTokenDeliveryService;

    @Inject
    private CIBAPingCallbackService cibaPingCallbackService;

    @Inject
    private ExternalPostAuthnService externalPostAuthnService;

    @Inject
    private CibaRequestService cibaRequestService;

    @Inject
    private DeviceAuthorizationService deviceAuthorizationService;

    @Inject
    private AttributeService attributeService;

    @Inject
    private ExternalUpdateTokenService externalUpdateTokenService;

    @Context
    private HttpServletRequest servletRequest;

    @Override
    public Response requestAuthorizationGet(
            String scope, String responseType, String clientId, String redirectUri, String state, String responseMode,
            String nonce, String display, String prompt, Integer maxAge, String uiLocales, String idTokenHint,
            String loginHint, String acrValues, String amrValues, String request, String requestUri,
            String requestSessionId, String sessionId, String originHeaders,
            String codeChallenge, String codeChallengeMethod, String customResponseHeaders, String claims, String authReqId,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse, SecurityContext securityContext) {
        return requestAuthorization(scope, responseType, clientId, redirectUri, state, responseMode, nonce, display,
                prompt, maxAge, uiLocales, idTokenHint, loginHint, acrValues, amrValues, request, requestUri,
                requestSessionId, sessionId, HttpMethod.GET, originHeaders, codeChallenge, codeChallengeMethod,
                customResponseHeaders, claims, authReqId, httpRequest, httpResponse, securityContext);
    }

    @Override
    public Response requestAuthorizationPost(
            String scope, String responseType, String clientId, String redirectUri, String state, String responseMode,
            String nonce, String display, String prompt, Integer maxAge, String uiLocales, String idTokenHint,
            String loginHint, String acrValues, String amrValues, String request, String requestUri,
            String requestSessionId, String sessionId, String originHeaders,
            String codeChallenge, String codeChallengeMethod, String customResponseHeaders, String claims,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse, SecurityContext securityContext) {
        return requestAuthorization(scope, responseType, clientId, redirectUri, state, responseMode, nonce, display,
                prompt, maxAge, uiLocales, idTokenHint, loginHint, acrValues, amrValues, request, requestUri,
                requestSessionId, sessionId, HttpMethod.POST, originHeaders, codeChallenge, codeChallengeMethod,
                customResponseHeaders, claims, null, httpRequest, httpResponse, securityContext);
    }

    private Response requestAuthorization(
            String scope, String responseType, String clientId, String redirectUri, String state, String respMode,
            String nonce, String display, String prompt, Integer maxAge, String uiLocalesStr, String idTokenHint,
            String loginHint, String acrValuesStr, String amrValuesStr, String request, String requestUri, String requestSessionId,
            String sessionId, String method, String originHeaders, String codeChallenge, String codeChallengeMethod,
            String customRespHeaders, String claims, String authReqId,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse, SecurityContext securityContext) {
        scope = ServerUtil.urlDecode(scope); // it may be encoded in uma case

        String tokenBindingHeader = httpRequest.getHeader("Sec-Token-Binding");

        OAuth2AuditLog oAuth2AuditLog = new OAuth2AuditLog(ServerUtil.getIpAddress(httpRequest), Action.USER_AUTHORIZATION);
        oAuth2AuditLog.setClientId(clientId);
        oAuth2AuditLog.setScope(scope);

        // ATTENTION : please do not add more parameter in this debug method because it will not work with Seam 2.2.2.Final ,
        // there is limit of 10 parameters (hardcoded), see: org.jboss.seam.core.Interpolator#interpolate
        log.debug("Attempting to request authorization: "
                        + "responseType = {}, clientId = {}, scope = {}, redirectUri = {}, nonce = {}, "
                        + "state = {}, request = {}, isSecure = {}, requestSessionId = {}, sessionId = {}",
                responseType, clientId, scope, redirectUri, nonce,
                state, request, securityContext.isSecure(), requestSessionId, sessionId);

        log.debug("Attempting to request authorization: "
                        + "acrValues = {}, amrValues = {}, originHeaders = {}, codeChallenge = {}, codeChallengeMethod = {}, "
                        + "customRespHeaders = {}, claims = {}, tokenBindingHeader = {}",
                acrValuesStr, amrValuesStr, originHeaders, codeChallenge, codeChallengeMethod, customRespHeaders, claims, tokenBindingHeader);

        ResponseBuilder builder = Response.ok();

        List<String> uiLocales = Util.splittedStringAsList(uiLocalesStr, " ");
        List<ResponseType> responseTypes = ResponseType.fromString(responseType, " ");
        List<Prompt> prompts = Prompt.fromString(prompt, " ");
        List<String> acrValues = Util.splittedStringAsList(acrValuesStr, " ");
        List<String> amrValues = Util.splittedStringAsList(amrValuesStr, " ");
        io.jans.as.model.common.ResponseMode responseMode = io.jans.as.model.common.ResponseMode.getByValue(respMode);

        Map<String, String> customParameters = requestParameterService.getCustomParameters(
                QueryStringDecoder.decode(httpRequest.getQueryString()));

        SessionId sessionUser = identity.getSessionId();
        User user = sessionIdService.getUser(sessionUser);

        try {
            Map<String, String> customResponseHeaders = Util.jsonObjectArrayStringAsMap(customRespHeaders);

            updateSessionForROPC(httpRequest, sessionUser);

            Client client = authorizeRestWebServiceValidator.validateClient(clientId, state);
            String deviceAuthzUserCode = deviceAuthorizationService.getUserCodeFromSession(httpRequest);
            redirectUri = authorizeRestWebServiceValidator.validateRedirectUri(client, redirectUri, state, deviceAuthzUserCode, httpRequest);
            checkAcrChanged(acrValuesStr, prompts, sessionUser); // check after redirect uri is validated

            RedirectUriResponse redirectUriResponse = new RedirectUriResponse(new RedirectUri(redirectUri, responseTypes, responseMode), state, httpRequest, errorResponseFactory);
            redirectUriResponse.setFapiCompatible(appConfiguration.getFapiCompatibility());

            Set<String> scopes = scopeChecker.checkScopesPolicy(client, scope);

            JwtAuthorizationRequest jwtRequest = null;
            if (StringUtils.isNotBlank(request) || StringUtils.isNotBlank(requestUri)) {
                try {
                    jwtRequest = JwtAuthorizationRequest.createJwtRequest(request, requestUri, client, redirectUriResponse, cryptoProvider, appConfiguration);

                    if (jwtRequest == null) {
                        throw createInvalidJwtRequestException(redirectUriResponse, "Failed to parse jwt.");
                    }
                    if (StringUtils.isNotBlank(jwtRequest.getState())) {
                        state = jwtRequest.getState();
                        redirectUriResponse.setState(state);
                    }
                    if (appConfiguration.getFapiCompatibility() && StringUtils.isBlank(jwtRequest.getState())) {
                        state = ""; // #1250 - FAPI : discard state if in JWT we don't have state
                        redirectUriResponse.setState("");
                    }

                    authorizeRestWebServiceValidator.validateRequestObject(jwtRequest, redirectUriResponse);

                    // MUST be equal
                    if (!jwtRequest.getResponseTypes().containsAll(responseTypes) || !responseTypes.containsAll(jwtRequest.getResponseTypes())) {
                        throw createInvalidJwtRequestException(redirectUriResponse, "The responseType parameter is not the same in the JWT");
                    }
                    if (StringUtils.isBlank(jwtRequest.getClientId()) || !jwtRequest.getClientId().equals(clientId)) {
                        throw createInvalidJwtRequestException(redirectUriResponse, "The clientId parameter is not the same in the JWT");
                    }

                    // JWT wins
                    if (!jwtRequest.getScopes().isEmpty()) {
                        if (!scopes.contains("openid")) { // spec: Even if a scope parameter is present in the Request Object value, a scope parameter MUST always be passed using the OAuth 2.0 request syntax containing the openid scope value
                            throw new WebApplicationException(Response
                                    .status(Response.Status.BAD_REQUEST)
                                    .entity(errorResponseFactory.getErrorAsJson(io.jans.as.model.authorize.AuthorizeErrorResponseType.INVALID_SCOPE, state, "scope parameter does not contain openid value which is required."))
                                    .build());
                        }
                        scopes = scopeChecker.checkScopesPolicy(client, Lists.newArrayList(jwtRequest.getScopes()));
                    }
                    if (jwtRequest.getRedirectUri() != null && !jwtRequest.getRedirectUri().equals(redirectUri)) {
                        throw createInvalidJwtRequestException(redirectUriResponse, "The redirect_uri parameter is not the same in the JWT");
                    }
                    if (StringUtils.isNotBlank(jwtRequest.getNonce())) {
                        nonce = jwtRequest.getNonce();
                    }
                    if (StringUtils.isNotBlank(jwtRequest.getCodeChallenge())) {
                        codeChallenge = jwtRequest.getCodeChallenge();
                    }
                    if (StringUtils.isNotBlank(jwtRequest.getCodeChallengeMethod())) {
                        codeChallengeMethod = jwtRequest.getCodeChallengeMethod();
                    }
                    if (jwtRequest.getDisplay() != null && StringUtils.isNotBlank(jwtRequest.getDisplay().getParamName())) {
                        display = jwtRequest.getDisplay().getParamName();
                    }
                    if (!jwtRequest.getPrompts().isEmpty()) {
                        prompts = Lists.newArrayList(jwtRequest.getPrompts());
                    }
                    if (jwtRequest.getResponseMode() != null) {
                        redirectUriResponse.getRedirectUri().setResponseMode(jwtRequest.getResponseMode());
                        responseMode = jwtRequest.getResponseMode();
                    }

                    final IdTokenMember idTokenMember = jwtRequest.getIdTokenMember();
                    if (idTokenMember != null) {
                        if (idTokenMember.getMaxAge() != null) {
                            maxAge = idTokenMember.getMaxAge();
                        }
                        final Claim acrClaim = idTokenMember.getClaim(JwtClaimName.AUTHENTICATION_CONTEXT_CLASS_REFERENCE);
                        if (acrClaim != null && acrClaim.getClaimValue() != null) {
                            acrValuesStr = acrClaim.getClaimValue().getValueAsString();
                            acrValues = Util.splittedStringAsList(acrValuesStr, " ");
                        }

                        Claim userIdClaim = idTokenMember.getClaim(JwtClaimName.SUBJECT_IDENTIFIER);
                        if (userIdClaim != null && userIdClaim.getClaimValue() != null
                                && userIdClaim.getClaimValue().getValue() != null) {
                            String userIdClaimValue = userIdClaim.getClaimValue().getValue();

                            if (user != null) {
                                String userId = user.getUserId();

                                if (!userId.equalsIgnoreCase(userIdClaimValue)) {
                                    builder = redirectUriResponse.createErrorBuilder(io.jans.as.model.authorize.AuthorizeErrorResponseType.USER_MISMATCHED);
                                    applicationAuditLogger.sendMessage(oAuth2AuditLog);
                                    return builder.build();
                                }
                            }
                        }
                    }
                    requestParameterService.getCustomParameters(jwtRequest, customParameters);
                } catch (WebApplicationException e) {
                    throw e;
                } catch (Exception e) {
                    log.error("Invalid JWT authorization request. Message : " + e.getMessage(), e);
                    throw createInvalidJwtRequestException(redirectUriResponse, "Invalid JWT authorization request");
                }
            }
            if (!cibaRequestService.hasCibaCompatibility(client)) {
                if (appConfiguration.getFapiCompatibility() && jwtRequest == null) {
                    throw redirectUriResponse.createWebException(io.jans.as.model.authorize.AuthorizeErrorResponseType.INVALID_REQUEST);
                }
                authorizeRestWebServiceValidator.validateRequestJwt(request, requestUri, redirectUriResponse);
            }
            authorizeRestWebServiceValidator.validate(responseTypes, prompts, nonce, state, redirectUri, httpRequest, client, responseMode);

            if (CollectionUtils.isEmpty(acrValues) && !ArrayUtils.isEmpty(client.getDefaultAcrValues())) {
                acrValues = Lists.newArrayList(client.getDefaultAcrValues());
            }

            if (scopes.contains(ScopeConstants.OFFLINE_ACCESS) && !client.getTrustedClient()) {
                if (!responseTypes.contains(ResponseType.CODE)) {
                    log.trace("Removed (ignored) offline_scope. Can't find `code` in response_type which is required.");
                    scopes.remove(ScopeConstants.OFFLINE_ACCESS);
                }

                if (scopes.contains(ScopeConstants.OFFLINE_ACCESS) && !prompts.contains(Prompt.CONSENT)) {
                    log.error("Removed offline_access. Can't find prompt=consent. Consent is required for offline_access.");
                    scopes.remove(ScopeConstants.OFFLINE_ACCESS);
                }
            }

            final boolean isResponseTypeValid = AuthorizeParamsValidator.validateResponseTypes(responseTypes, client)
                    && AuthorizeParamsValidator.validateGrantType(responseTypes, client.getGrantTypes(), appConfiguration);

            if (!isResponseTypeValid) {
                throw new WebApplicationException(Response
                        .status(Response.Status.BAD_REQUEST)
                        .entity(errorResponseFactory.getErrorAsJson(io.jans.as.model.authorize.AuthorizeErrorResponseType.UNSUPPORTED_RESPONSE_TYPE, state, ""))
                        .build());
            }

            AuthorizationGrant authorizationGrant = null;

            if (user == null) {
                identity.logout();
                if (prompts.contains(io.jans.as.model.common.Prompt.NONE)) {
                    if (authenticationFilterService.isEnabled()) {
                        Map<String, String> params;
                        if (method.equals(HttpMethod.GET)) {
                            params = QueryStringDecoder.decode(httpRequest.getQueryString());
                        } else {
                            params = getGenericRequestMap(httpRequest);
                        }

                        String userDn = authenticationFilterService.processAuthenticationFilters(params);
                        if (userDn != null) {
                            Map<String, String> genericRequestMap = getGenericRequestMap(httpRequest);

                            Map<String, String> parameterMap = Maps.newHashMap(genericRequestMap);
                            Map<String, String> requestParameterMap = requestParameterService.getAllowedParameters(parameterMap);

                            sessionUser = sessionIdService.generateAuthenticatedSessionId(httpRequest, userDn, prompt);
                            sessionUser.setSessionAttributes(requestParameterMap);

                            cookieService.createSessionIdCookie(sessionUser, httpRequest, httpResponse, false);
                            sessionIdService.updateSessionId(sessionUser);
                            user = userService.getUserByDn(sessionUser.getUserDn());
                        } else {
                            builder = redirectUriResponse.createErrorBuilder(io.jans.as.model.authorize.AuthorizeErrorResponseType.LOGIN_REQUIRED);
                            applicationAuditLogger.sendMessage(oAuth2AuditLog);
                            return builder.build();
                        }
                    } else {
                        builder = redirectUriResponse.createErrorBuilder(io.jans.as.model.authorize.AuthorizeErrorResponseType.LOGIN_REQUIRED);
                        applicationAuditLogger.sendMessage(oAuth2AuditLog);
                        return builder.build();
                    }
                } else {
                    if (prompts.contains(io.jans.as.model.common.Prompt.LOGIN)) {
                        unauthenticateSession(sessionId, httpRequest);
                        sessionId = null;
                        prompts.remove(io.jans.as.model.common.Prompt.LOGIN);
                    }

                    return redirectToAuthorizationPage(redirectUriResponse.getRedirectUri(), responseTypes, scope, clientId,
                            redirectUri, state, responseMode, nonce, display, prompts, maxAge, uiLocales,
                            idTokenHint, loginHint, acrValues, amrValues, request, requestUri, originHeaders,
                            codeChallenge, codeChallengeMethod, sessionId, claims, authReqId, customParameters, oAuth2AuditLog, httpRequest);
                }
            }

            boolean validAuthenticationMaxAge = authorizeRestWebServiceValidator.validateAuthnMaxAge(maxAge, sessionUser, client);
            if (!validAuthenticationMaxAge) {
                unauthenticateSession(sessionId, httpRequest);
                sessionId = null;

                return redirectToAuthorizationPage(redirectUriResponse.getRedirectUri(), responseTypes, scope, clientId,
                        redirectUri, state, responseMode, nonce, display, prompts, maxAge, uiLocales,
                        idTokenHint, loginHint, acrValues, amrValues, request, requestUri, originHeaders,
                        codeChallenge, codeChallengeMethod, sessionId, claims, authReqId, customParameters, oAuth2AuditLog, httpRequest);
            }

            oAuth2AuditLog.setUsername(user.getUserId());

            ExternalPostAuthnContext postAuthnContext = new ExternalPostAuthnContext(client, sessionUser, httpRequest, httpResponse);
            final boolean forceReAuthentication = externalPostAuthnService.externalForceReAuthentication(client, postAuthnContext);
            if (forceReAuthentication) {
                unauthenticateSession(sessionId, httpRequest);
                sessionId = null;

                return redirectToAuthorizationPage(redirectUriResponse.getRedirectUri(), responseTypes, scope, clientId,
                        redirectUri, state, responseMode, nonce, display, prompts, maxAge, uiLocales,
                        idTokenHint, loginHint, acrValues, amrValues, request, requestUri, originHeaders,
                        codeChallenge, codeChallengeMethod, sessionId, claims, authReqId, customParameters, oAuth2AuditLog, httpRequest);
            }

            final boolean forceAuthorization = externalPostAuthnService.externalForceAuthorization(client, postAuthnContext);
            if (forceAuthorization) {
                return redirectToAuthorizationPage(redirectUriResponse.getRedirectUri(), responseTypes, scope, clientId,
                        redirectUri, state, responseMode, nonce, display, prompts, maxAge, uiLocales,
                        idTokenHint, loginHint, acrValues, amrValues, request, requestUri, originHeaders,
                        codeChallenge, codeChallengeMethod, sessionId, claims, authReqId, customParameters, oAuth2AuditLog, httpRequest);
            }

            ClientAuthorization clientAuthorization = null;
            boolean clientAuthorizationFetched = false;
            if (scopes.size() > 0) {
                if (prompts.contains(io.jans.as.model.common.Prompt.CONSENT)) {
                    return redirectToAuthorizationPage(redirectUriResponse.getRedirectUri(), responseTypes, scope, clientId,
                            redirectUri, state, responseMode, nonce, display, prompts, maxAge, uiLocales,
                            idTokenHint, loginHint, acrValues, amrValues, request, requestUri, originHeaders,
                            codeChallenge, codeChallengeMethod, sessionId, claims, authReqId, customParameters, oAuth2AuditLog, httpRequest);
                }
                if (client.getTrustedClient()) {
                    sessionUser.addPermission(clientId, true);
                    sessionIdService.updateSessionId(sessionUser);
                } else {
                    clientAuthorization = clientAuthorizationsService.find(user.getAttribute("inum"), client.getClientId());
                    clientAuthorizationFetched = true;
                    if (clientAuthorization != null && clientAuthorization.getScopes() != null) {
                        log.trace("ClientAuthorization - scope: " + scope + ", dn: " + clientAuthorization.getDn() + ", requestedScope: " + scopes);
                        if (Arrays.asList(clientAuthorization.getScopes()).containsAll(scopes)) {
                            sessionUser.addPermission(clientId, true);
                            sessionIdService.updateSessionId(sessionUser);
                        } else {
                            return redirectToAuthorizationPage(redirectUriResponse.getRedirectUri(), responseTypes, scope, clientId,
                                    redirectUri, state, responseMode, nonce, display, prompts, maxAge, uiLocales,
                                    idTokenHint, loginHint, acrValues, amrValues, request, requestUri, originHeaders,
                                    codeChallenge, codeChallengeMethod, sessionId, claims, authReqId, customParameters, oAuth2AuditLog, httpRequest);
                        }
                    }
                }
            }

            if (prompts.contains(io.jans.as.model.common.Prompt.LOGIN)) {

                //  workaround for #1030 - remove only authenticated session, for set up acr we set it unauthenticated and then drop in AuthorizeAction
                if (identity.getSessionId().getState() == SessionIdState.AUTHENTICATED) {
                    unauthenticateSession(sessionId, httpRequest);
                }
                sessionId = null;
                prompts.remove(io.jans.as.model.common.Prompt.LOGIN);

                return redirectToAuthorizationPage(redirectUriResponse.getRedirectUri(), responseTypes, scope, clientId,
                        redirectUri, state, responseMode, nonce, display, prompts, maxAge, uiLocales,
                        idTokenHint, loginHint, acrValues, amrValues, request, requestUri, originHeaders,
                        codeChallenge, codeChallengeMethod, sessionId, claims, authReqId, customParameters, oAuth2AuditLog, httpRequest);
            }

            if (prompts.contains(io.jans.as.model.common.Prompt.CONSENT) || !sessionUser.isPermissionGrantedForClient(clientId)) {
                if (!clientAuthorizationFetched) {
                    clientAuthorization = clientAuthorizationsService.find(user.getAttribute("inum"), client.getClientId());
                }
                clientAuthorizationsService.clearAuthorizations(clientAuthorization, client.getPersistClientAuthorizations());

                prompts.remove(io.jans.as.model.common.Prompt.CONSENT);

                return redirectToAuthorizationPage(redirectUriResponse.getRedirectUri(), responseTypes, scope, clientId,
                        redirectUri, state, responseMode, nonce, display, prompts, maxAge, uiLocales,
                        idTokenHint, loginHint, acrValues, amrValues, request, requestUri, originHeaders,
                        codeChallenge, codeChallengeMethod, sessionId, claims, authReqId, customParameters, oAuth2AuditLog, httpRequest);
            }

            if (prompts.contains(io.jans.as.model.common.Prompt.SELECT_ACCOUNT)) {
                return redirectToSelectAccountPage(redirectUriResponse.getRedirectUri(), responseTypes, scope, clientId,
                        redirectUri, state, responseMode, nonce, display, prompts, maxAge, uiLocales,
                        idTokenHint, loginHint, acrValues, amrValues, request, requestUri, originHeaders,
                        codeChallenge, codeChallengeMethod, sessionId, claims, authReqId, customParameters, oAuth2AuditLog, httpRequest);
            }

            AuthorizationCode authorizationCode = null;
            if (responseTypes.contains(io.jans.as.model.common.ResponseType.CODE)) {
                authorizationGrant = authorizationGrantList.createAuthorizationCodeGrant(user, client,
                        sessionUser.getAuthenticationTime());
                authorizationGrant.setNonce(nonce);
                authorizationGrant.setJwtAuthorizationRequest(jwtRequest);
                authorizationGrant.setTokenBindingHash(TokenBindingMessage.getTokenBindingIdHashFromTokenBindingMessage(tokenBindingHeader, client.getIdTokenTokenBindingCnf()));
                authorizationGrant.setScopes(scopes);
                authorizationGrant.setCodeChallenge(codeChallenge);
                authorizationGrant.setCodeChallengeMethod(codeChallengeMethod);
                authorizationGrant.setClaims(claims);

                // Store acr_values
                authorizationGrant.setAcrValues(getAcrForGrant(acrValuesStr, sessionUser));
                authorizationGrant.setSessionDn(sessionUser.getDn());
                authorizationGrant.save(); // call save after object modification!!!

                authorizationCode = authorizationGrant.getAuthorizationCode();

                redirectUriResponse.getRedirectUri().addResponseParameter("code", authorizationCode.getCode());
            }

            AccessToken newAccessToken = null;
            if (responseTypes.contains(io.jans.as.model.common.ResponseType.TOKEN)) {
                if (authorizationGrant == null) {
                    authorizationGrant = authorizationGrantList.createImplicitGrant(user, client,
                            sessionUser.getAuthenticationTime());
                    authorizationGrant.setNonce(nonce);
                    authorizationGrant.setJwtAuthorizationRequest(jwtRequest);
                    authorizationGrant.setScopes(scopes);
                    authorizationGrant.setClaims(claims);

                    // Store acr_values
                    authorizationGrant.setAcrValues(getAcrForGrant(acrValuesStr, sessionUser));
                    authorizationGrant.setSessionDn(sessionUser.getDn());
                    authorizationGrant.save(); // call save after object modification!!!
                }
                newAccessToken = authorizationGrant.createAccessToken(httpRequest.getHeader("X-ClientCert"), new ExecutionContext(httpRequest, httpResponse));

                redirectUriResponse.getRedirectUri().addResponseParameter(AuthorizeResponseParam.ACCESS_TOKEN, newAccessToken.getCode());
                redirectUriResponse.getRedirectUri().addResponseParameter(AuthorizeResponseParam.TOKEN_TYPE, newAccessToken.getTokenType().toString());
                redirectUriResponse.getRedirectUri().addResponseParameter(AuthorizeResponseParam.EXPIRES_IN, newAccessToken.getExpiresIn() + "");
            }

            if (responseTypes.contains(io.jans.as.model.common.ResponseType.ID_TOKEN)) {
                boolean includeIdTokenClaims = Boolean.TRUE.equals(appConfiguration.getLegacyIdTokenClaims());
                if (authorizationGrant == null) {
                    includeIdTokenClaims = true;
                    authorizationGrant = authorizationGrantList.createImplicitGrant(user, client,
                            sessionUser.getAuthenticationTime());
                    authorizationGrant.setNonce(nonce);
                    authorizationGrant.setJwtAuthorizationRequest(jwtRequest);
                    authorizationGrant.setScopes(scopes);
                    authorizationGrant.setClaims(claims);

                    // Store authentication acr values
                    authorizationGrant.setAcrValues(getAcrForGrant(acrValuesStr, sessionUser));
                    authorizationGrant.setSessionDn(sessionUser.getDn());
                    authorizationGrant.save(); // call save after object modification, call is asynchronous!!!
                }

                ExternalUpdateTokenContext context = new ExternalUpdateTokenContext(httpRequest, authorizationGrant, client, appConfiguration, attributeService);
                Function<JsonWebResponse, Void> postProcessor = externalUpdateTokenService.buildModifyIdTokenProcessor(context);

                IdToken idToken = authorizationGrant.createIdToken(
                        nonce, authorizationCode, newAccessToken, null,
                        state, authorizationGrant, includeIdTokenClaims,
                        JwrService.wrapWithSidFunction(TokenBindingMessage.createIdTokenTokingBindingPreprocessing(tokenBindingHeader, client.getIdTokenTokenBindingCnf()), sessionUser.getOutsideSid()),
                        postProcessor);

                redirectUriResponse.getRedirectUri().addResponseParameter(AuthorizeResponseParam.ID_TOKEN, idToken.getCode());
            }

            if (authorizationGrant != null && StringHelper.isNotEmpty(acrValuesStr) && !appConfiguration.getFapiCompatibility()) {
                redirectUriResponse.getRedirectUri().addResponseParameter(AuthorizeResponseParam.ACR_VALUES, acrValuesStr);
            }

            if (sessionUser.getId() == null) {
                final SessionId newSessionUser = sessionIdService.generateAuthenticatedSessionId(httpRequest, sessionUser.getUserDn(), prompt);
                String newSessionId = newSessionUser.getId();
                sessionUser.setId(newSessionId);
                log.trace("newSessionId = {}", newSessionId);
            }
            if (!appConfiguration.getFapiCompatibility()) {
                redirectUriResponse.getRedirectUri().addResponseParameter(AuthorizeResponseParam.SESSION_ID, sessionUser.getId());
            }
            redirectUriResponse.getRedirectUri().addResponseParameter(AuthorizeResponseParam.SID, sessionUser.getOutsideSid());
            redirectUriResponse.getRedirectUri().addResponseParameter(AuthorizeResponseParam.SESSION_STATE, sessionIdService.computeSessionState(sessionUser, clientId, redirectUri));
            redirectUriResponse.getRedirectUri().addResponseParameter(AuthorizeResponseParam.STATE, state);
            if (scope != null && !scope.isEmpty() && authorizationGrant != null && !appConfiguration.getFapiCompatibility()) {
                scope = authorizationGrant.checkScopesPolicy(scope);

                redirectUriResponse.getRedirectUri().addResponseParameter(AuthorizeResponseParam.SCOPE, scope);
            }

            clientService.updateAccessTime(client, false);
            oAuth2AuditLog.setSuccess(true);

            builder = RedirectUtil.getRedirectResponseBuilder(redirectUriResponse.getRedirectUri(), httpRequest);

            if (appConfiguration.getCustomHeadersWithAuthorizationResponse()) {
                for (String key : customResponseHeaders.keySet()) {
                    builder.header(key, customResponseHeaders.get(key));
                }
            }

            if (StringUtils.isNotBlank(authReqId)) {
                runCiba(authReqId, client, httpRequest, httpResponse);
            }
            if (StringUtils.isNotBlank(deviceAuthzUserCode)) {
                processDeviceAuthorization(deviceAuthzUserCode, user);
            }
        } catch (WebApplicationException e) {
            applicationAuditLogger.sendMessage(oAuth2AuditLog);
            log.error(e.getMessage(), e);
            throw e;
        } catch (AcrChangedException e) { // Acr changed
            log.error("ACR is changed, please provide a supported and enabled acr value");
            log.error(e.getMessage(), e);

            RedirectUri redirectUriResponse = new RedirectUri(redirectUri, responseTypes, responseMode);
            redirectUriResponse.parseQueryString(errorResponseFactory.getErrorAsQueryString(
                    io.jans.as.model.authorize.AuthorizeErrorResponseType.SESSION_SELECTION_REQUIRED, state));
            redirectUriResponse.addResponseParameter("hint", "Use prompt=login in order to alter existing session.");
            applicationAuditLogger.sendMessage(oAuth2AuditLog);
            return RedirectUtil.getRedirectResponseBuilder(redirectUriResponse, httpRequest).build();
        } catch (EntryPersistenceException e) { // Invalid clientId
            builder = Response.status(Response.Status.UNAUTHORIZED.getStatusCode())
                    .entity(errorResponseFactory.getErrorAsJson(io.jans.as.model.authorize.AuthorizeErrorResponseType.UNAUTHORIZED_CLIENT, state, ""))
                    .type(MediaType.APPLICATION_JSON_TYPE);
            log.error(e.getMessage(), e);
        } catch (InvalidSessionStateException ex) { // Allow to handle it via GlobalExceptionHandler
            throw ex;
        } catch (Exception e) {
            builder = Response.status(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode()); // 500
            log.error(e.getMessage(), e);
        }

        applicationAuditLogger.sendMessage(oAuth2AuditLog);
        return builder.build();
    }

    private String getAcrForGrant(String acrValuesStr, SessionId sessionUser) {
        final String acr = sessionIdService.getAcr(sessionUser);
        return StringUtils.isNotBlank(acr) ? acr : acrValuesStr;
    }

    private void runCiba(String authReqId, Client client, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        CibaRequestCacheControl cibaRequest = cibaRequestService.getCibaRequest(authReqId);

        if (cibaRequest == null || cibaRequest.getStatus() == CibaRequestStatus.EXPIRED) {
            log.trace("User responded too late and the grant {} has expired, {}", authReqId, cibaRequest);
            return;
        }

        cibaRequestService.removeCibaRequest(authReqId);
        CIBAGrant cibaGrant = authorizationGrantList.createCIBAGrant(cibaRequest);

        RefreshToken refreshToken = cibaGrant.createRefreshToken();
        log.debug("Issuing refresh token: {}", refreshToken.getCode());

        AccessToken accessToken = cibaGrant.createAccessToken(httpRequest.getHeader("X-ClientCert"), new ExecutionContext(httpRequest, httpResponse));
        log.debug("Issuing access token: {}", accessToken.getCode());

        ExternalUpdateTokenContext context = new ExternalUpdateTokenContext(httpRequest, cibaGrant, client, appConfiguration, attributeService);
        Function<JsonWebResponse, Void> postProcessor = externalUpdateTokenService.buildModifyIdTokenProcessor(context);

        IdToken idToken = cibaGrant.createIdToken(
                null, null, accessToken, refreshToken,
                null, cibaGrant, false, null, postProcessor);

        cibaGrant.setTokensDelivered(true);
        cibaGrant.save();

        if (cibaRequest.getClient().getBackchannelTokenDeliveryMode() == io.jans.as.model.common.BackchannelTokenDeliveryMode.PUSH) {
            cibaPushTokenDeliveryService.pushTokenDelivery(
                    cibaGrant.getAuthReqId(),
                    cibaGrant.getClient().getBackchannelClientNotificationEndpoint(),
                    cibaRequest.getClientNotificationToken(),
                    accessToken.getCode(),
                    refreshToken.getCode(),
                    idToken.getCode(),
                    accessToken.getExpiresIn()
            );
        } else if (cibaGrant.getClient().getBackchannelTokenDeliveryMode() == io.jans.as.model.common.BackchannelTokenDeliveryMode.PING) {
            cibaGrant.setTokensDelivered(false);
            cibaGrant.save();

            cibaPingCallbackService.pingCallback(
                    cibaGrant.getAuthReqId(),
                    cibaGrant.getClient().getBackchannelClientNotificationEndpoint(),
                    cibaRequest.getClientNotificationToken()
            );
        } else if (cibaGrant.getClient().getBackchannelTokenDeliveryMode() == io.jans.as.model.common.BackchannelTokenDeliveryMode.POLL) {
            cibaGrant.setTokensDelivered(false);
            cibaGrant.save();
        }
    }

    private WebApplicationException createInvalidJwtRequestException(RedirectUriResponse redirectUriResponse, String reason) {
        if (appConfiguration.getFapiCompatibility()) {
            log.debug(reason); // in FAPI case log reason but don't send it since it's `reason` is not known.
            return redirectUriResponse.createWebException(io.jans.as.model.authorize.AuthorizeErrorResponseType.INVALID_REQUEST_OBJECT);
        }
        return redirectUriResponse.createWebException(AuthorizeErrorResponseType.INVALID_REQUEST_OBJECT, reason);
    }

    private void updateSessionForROPC(HttpServletRequest httpRequest, SessionId sessionUser) {
        if (sessionUser == null) {
            return;
        }

        Map<String, String> sessionAttributes = sessionUser.getSessionAttributes();
        String authorizedGrant = sessionUser.getSessionAttributes().get(Constants.AUTHORIZED_GRANT);
        if (StringHelper.isNotEmpty(authorizedGrant) && io.jans.as.model.common.GrantType.RESOURCE_OWNER_PASSWORD_CREDENTIALS == GrantType.fromString(authorizedGrant)) {
            // Remove from session to avoid execution on next AuthZ request
            sessionAttributes.remove(Constants.AUTHORIZED_GRANT);

            // Reset AuthZ parameters
            Map<String, String> parameterMap = getGenericRequestMap(httpRequest);
            Map<String, String> requestParameterMap = requestParameterService.getAllowedParameters(parameterMap);
            sessionAttributes.putAll(requestParameterMap);
            sessionIdService.updateSessionId(sessionUser, true, true, true);
        }
    }

    private void checkAcrChanged(String acrValuesStr, List<io.jans.as.model.common.Prompt> prompts, SessionId sessionUser) throws AcrChangedException {
        try {
            sessionIdService.assertAuthenticatedSessionCorrespondsToNewRequest(sessionUser, acrValuesStr);
        } catch (AcrChangedException e) { // Acr changed
            //See https://github.com/GluuFederation/oxTrust/issues/797
            if (e.isForceReAuthentication()) {
                if (!prompts.contains(io.jans.as.model.common.Prompt.LOGIN)) {
                    log.info("ACR is changed, adding prompt=login to prompts");
                    prompts.add(io.jans.as.model.common.Prompt.LOGIN);

                    sessionUser.setState(SessionIdState.UNAUTHENTICATED);
                    sessionUser.getSessionAttributes().put("prompt", io.jans.as.model.util.StringUtils.implode(prompts, " "));
                    sessionIdService.persistSessionId(sessionUser);
                    sessionIdService.externalEvent(new SessionEvent(SessionEventType.UNAUTHENTICATED, sessionUser));
                }
            } else {
                throw e;
            }
        }
    }

    private Map<String, String> getGenericRequestMap(HttpServletRequest httpRequest) {
        Map<String, String> result = new HashMap<>();
        for (Entry<String, String[]> entry : httpRequest.getParameterMap().entrySet()) {
            result.put(entry.getKey(), entry.getValue()[0]);
        }

        return result;
    }

    private Response redirectToAuthorizationPage(RedirectUri redirectUriResponse, List<io.jans.as.model.common.ResponseType> responseTypes, String scope, String clientId,
                                                 String redirectUri, String state, io.jans.as.model.common.ResponseMode responseMode, String nonce, String display,
                                                 List<io.jans.as.model.common.Prompt> prompts, Integer maxAge, List<String> uiLocales, String idTokenHint, String loginHint,
                                                 List<String> acrValues, List<String> amrValues, String request, String requestUri, String originHeaders,
                                                 String codeChallenge, String codeChallengeMethod, String sessionId, String claims, String authReqId,
                                                 Map<String, String> customParameters, OAuth2AuditLog oAuth2AuditLog, HttpServletRequest httpRequest) {
        return redirectTo("/authorize", redirectUriResponse, responseTypes, scope, clientId, redirectUri,
                state, responseMode, nonce, display, prompts, maxAge, uiLocales, idTokenHint, loginHint, acrValues, amrValues, request, requestUri, originHeaders,
                codeChallenge, codeChallengeMethod, sessionId, claims, authReqId, customParameters, oAuth2AuditLog, httpRequest);
    }

    private Response redirectToSelectAccountPage(RedirectUri redirectUriResponse, List<io.jans.as.model.common.ResponseType> responseTypes, String scope, String clientId,
                                                 String redirectUri, String state, io.jans.as.model.common.ResponseMode responseMode, String nonce, String display,
                                                 List<io.jans.as.model.common.Prompt> prompts, Integer maxAge, List<String> uiLocales, String idTokenHint, String loginHint,
                                                 List<String> acrValues, List<String> amrValues, String request, String requestUri, String originHeaders,
                                                 String codeChallenge, String codeChallengeMethod, String sessionId, String claims, String authReqId,
                                                 Map<String, String> customParameters, OAuth2AuditLog oAuth2AuditLog, HttpServletRequest httpRequest) {
        return redirectTo("/selectAccount", redirectUriResponse, responseTypes, scope, clientId, redirectUri,
                state, responseMode, nonce, display, prompts, maxAge, uiLocales, idTokenHint, loginHint, acrValues, amrValues, request, requestUri, originHeaders,
                codeChallenge, codeChallengeMethod, sessionId, claims, authReqId, customParameters, oAuth2AuditLog, httpRequest);
    }

    private Response redirectTo(String pathToRedirect,
                                RedirectUri redirectUriResponse, List<io.jans.as.model.common.ResponseType> responseTypes, String scope, String clientId,
                                String redirectUri, String state, io.jans.as.model.common.ResponseMode responseMode, String nonce, String display,
                                List<io.jans.as.model.common.Prompt> prompts, Integer maxAge, List<String> uiLocales, String idTokenHint, String loginHint,
                                List<String> acrValues, List<String> amrValues, String request, String requestUri, String originHeaders,
                                String codeChallenge, String codeChallengeMethod, String sessionId, String claims, String authReqId,
                                Map<String, String> customParameters, OAuth2AuditLog oAuth2AuditLog, HttpServletRequest httpRequest) {

        final URI contextUri = URI.create(appConfiguration.getIssuer()).resolve(servletRequest.getContextPath() + pathToRedirect + сonfigurationFactory.getFacesMapping());

        redirectUriResponse.setBaseRedirectUri(contextUri.toString());
        redirectUriResponse.setResponseMode(io.jans.as.model.common.ResponseMode.QUERY);

        // oAuth parameters
        String responseType = implode(responseTypes, " ");
        if (StringUtils.isNotBlank(responseType)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.RESPONSE_TYPE, responseType);
        }
        if (StringUtils.isNotBlank(scope)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.SCOPE, scope);
        }
        if (StringUtils.isNotBlank(clientId)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.CLIENT_ID, clientId);
        }
        if (StringUtils.isNotBlank(redirectUri)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.REDIRECT_URI, redirectUri);
        }
        if (StringUtils.isNotBlank(state)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.STATE, state);
        }
        if (responseMode != null) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.RESPONSE_MODE, responseMode.getParamName());
        }

        // OIC parameters
        if (StringUtils.isNotBlank(nonce)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.NONCE, nonce);
        }
        if (StringUtils.isNotBlank(display)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.DISPLAY, display);
        }
        String prompt = implode(prompts, " ");
        if (StringUtils.isNotBlank(prompt)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.PROMPT, prompt);
        }
        if (maxAge != null) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.MAX_AGE, maxAge.toString());
        }
        String uiLocalesStr = implode(uiLocales, " ");
        if (StringUtils.isNotBlank(uiLocalesStr)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.UI_LOCALES, uiLocalesStr);
        }
        if (StringUtils.isNotBlank(idTokenHint)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.ID_TOKEN_HINT, idTokenHint);
        }
        if (StringUtils.isNotBlank(loginHint)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.LOGIN_HINT, loginHint);
        }
        String acrValuesStr = implode(acrValues, " ");
        if (StringUtils.isNotBlank(acrValuesStr)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.ACR_VALUES, acrValuesStr);
        }
        String amrValuesStr = implode(amrValues, " ");
        if (StringUtils.isNotBlank(amrValuesStr)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.AMR_VALUES, amrValuesStr);
        }
        if (StringUtils.isNotBlank(request)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.REQUEST, request);
        }
        if (StringUtils.isNotBlank(requestUri)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.REQUEST_URI, requestUri);
        }
        if (StringUtils.isNotBlank(codeChallenge)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.CODE_CHALLENGE, codeChallenge);
        }
        if (StringUtils.isNotBlank(codeChallengeMethod)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.CODE_CHALLENGE_METHOD, codeChallengeMethod);
        }
        if (StringUtils.isNotBlank(sessionId) && appConfiguration.getSessionIdRequestParameterEnabled()) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.SESSION_ID, sessionId);
        }
        if (StringUtils.isNotBlank(claims)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.CLAIMS, claims);
        }

        // CIBA param
        if (StringUtils.isNotBlank(authReqId)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.AUTH_REQ_ID, authReqId);
        }

        // mod_ox param
        if (StringUtils.isNotBlank(originHeaders)) {
            redirectUriResponse.addResponseParameter(io.jans.as.model.authorize.AuthorizeRequestParam.ORIGIN_HEADERS, originHeaders);
        }

        if (customParameters != null && customParameters.size() > 0) {
            for (Map.Entry<String, String> entry : customParameters.entrySet()) {
                redirectUriResponse.addResponseParameter(entry.getKey(), entry.getValue());
            }
        }

        ResponseBuilder builder = RedirectUtil.getRedirectResponseBuilder(redirectUriResponse, httpRequest);
        applicationAuditLogger.sendMessage(oAuth2AuditLog);
        return builder.build();
    }

    private void unauthenticateSession(String sessionId, HttpServletRequest httpRequest) {
        identity.logout();

        SessionId sessionUser = identity.getSessionId();

        if (sessionUser != null) {
            sessionUser.setUserDn(null);
            sessionUser.setUser(null);
            sessionUser.setAuthenticationTime(null);
        }

        if (StringHelper.isEmpty(sessionId)) {
            sessionId = cookieService.getSessionIdFromCookie(httpRequest);
        }

        SessionId persistenceSessionId = sessionIdService.getSessionId(sessionId);
        if (persistenceSessionId == null) {
            log.error("Failed to load session from LDAP by session_id: '{}'", sessionId);
            return;
        }

        persistenceSessionId.setState(SessionIdState.UNAUTHENTICATED);
        persistenceSessionId.setUserDn(null);
        persistenceSessionId.setUser(null);
        persistenceSessionId.setAuthenticationTime(null);
        boolean result = sessionIdService.updateSessionId(persistenceSessionId);
        sessionIdService.externalEvent(new SessionEvent(SessionEventType.UNAUTHENTICATED, persistenceSessionId).setHttpRequest(httpRequest));
        if (!result) {
            log.error("Failed to update session_id '{}'", sessionId);
        }
    }

    /**
     * Processes an authorization granted for device code grant type.
     * @param userCode User code used in the device code flow.
     * @param user Authenticated user that is giving the permissions.
     */
    private void processDeviceAuthorization(String userCode, User user) {
        DeviceAuthorizationCacheControl cacheData = deviceAuthorizationService.getDeviceAuthzByUserCode(userCode);
        if (cacheData == null || cacheData.getStatus() == DeviceAuthorizationStatus.EXPIRED) {
            log.trace("User responded too late and the authorization {} has expired, {}", userCode, cacheData);
            return;
        }

        deviceAuthorizationService.removeDeviceAuthRequestInCache(userCode, cacheData.getDeviceCode());
        DeviceCodeGrant deviceCodeGrant = authorizationGrantList.createDeviceGrant(cacheData, user);

        log.info("Granted device authorization request, user_code: {}, device_code: {}, grant_id: {}", userCode, cacheData.getDeviceCode(), deviceCodeGrant.getGrantId());
    }

}