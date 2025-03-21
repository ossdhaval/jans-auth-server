/*
 * Janssen Project software is available under the Apache License (2004). See http://www.apache.org/licenses/ for full text.
 *
 * Copyright (c) 2020, Janssen Project
 */

package io.jans.as.server.model.authorize;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import io.jans.as.common.model.registration.Client;
import io.jans.as.model.authorize.AuthorizeErrorResponseType;
import io.jans.as.model.common.Display;
import io.jans.as.model.common.Prompt;
import io.jans.as.model.common.ResponseMode;
import io.jans.as.model.common.ResponseType;
import io.jans.as.model.configuration.AppConfiguration;
import io.jans.as.model.crypto.AbstractCryptoProvider;
import io.jans.as.model.crypto.encryption.BlockEncryptionAlgorithm;
import io.jans.as.model.crypto.encryption.KeyEncryptionAlgorithm;
import io.jans.as.model.crypto.signature.SignatureAlgorithm;
import io.jans.as.model.exception.InvalidJwtException;
import io.jans.as.model.jwe.Jwe;
import io.jans.as.model.jwe.JweDecrypterImpl;
import io.jans.as.model.jwt.JwtHeader;
import io.jans.as.model.jwt.JwtHeaderName;
import io.jans.as.model.util.Base64Util;
import io.jans.as.model.util.JwtUtil;
import io.jans.as.model.util.Util;
import io.jans.as.server.service.ClientService;
import io.jans.as.server.service.RedirectUriResponse;
import io.jans.service.cdi.util.CdiUtil;
import org.apache.commons.lang.StringUtils;
import org.jboss.resteasy.client.ClientRequest;
import org.jboss.resteasy.client.ClientResponse;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.WebApplicationException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Javier Rojas Blum
 * @version November 20, 2018
 */
public class JwtAuthorizationRequest {

    private final static Logger log = LoggerFactory.getLogger(JwtAuthorizationRequest.class);

    // Header
    private String type;
    private String algorithm;
    private String encryptionAlgorithm;
    private String keyId;

    // Payload
    private List<ResponseType> responseTypes;
    private String clientId;
    private List<String> scopes;
    private String redirectUri;
    private String nonce;
    private String state;
    private List<String> aud = Lists.newArrayList();
    private Display display;
    private List<Prompt> prompts;
    private UserInfoMember userInfoMember;
    private IdTokenMember idTokenMember;
    private Integer exp;
    private String iss;
    private Integer iat;
    private Integer nbf;
    private String jti;
    private String clientNotificationToken;
    private String acrValues;
    private String loginHintToken;
    private String idTokenHint;
    private String loginHint;
    private String bindingMessage;
    private String userCode;
    private String codeChallenge;
    private String codeChallengeMethod;
    private Integer requestedExpiry;
    private ResponseMode responseMode;

    private String encodedJwt;
    private String payload;

    private AppConfiguration appConfiguration;

    public JwtAuthorizationRequest(AppConfiguration appConfiguration, AbstractCryptoProvider cryptoProvider, String encodedJwt, Client client) throws InvalidJwtException {
        try {
            this.appConfiguration = appConfiguration;
            this.responseTypes = new ArrayList<>();
            this.scopes = new ArrayList<>();
            this.prompts = new ArrayList<>();
            this.encodedJwt = encodedJwt;

            if (StringUtils.isEmpty(encodedJwt)) {
                throw new InvalidJwtException("The JWT is null or empty");
            }


            String[] parts = encodedJwt.split("\\.");

            if (parts.length == 5) {
                String encodedHeader = parts[0];

                JwtHeader jwtHeader = new JwtHeader(encodedHeader);

                keyId = jwtHeader.getKeyId();
                KeyEncryptionAlgorithm keyEncryptionAlgorithm = KeyEncryptionAlgorithm.fromName(
                        jwtHeader.getClaimAsString(JwtHeaderName.ALGORITHM));
                BlockEncryptionAlgorithm blockEncryptionAlgorithm = BlockEncryptionAlgorithm.fromName(
                        jwtHeader.getClaimAsString(JwtHeaderName.ENCRYPTION_METHOD));

                JweDecrypterImpl jweDecrypter = null;
                if ("RSA".equals(keyEncryptionAlgorithm.getFamily())) {
                    PrivateKey privateKey = cryptoProvider.getPrivateKey(keyId);
                    jweDecrypter = new JweDecrypterImpl(privateKey);
                } else {
                    ClientService clientService = CdiUtil.bean(ClientService.class);
                    jweDecrypter = new JweDecrypterImpl(clientService.decryptSecret(client.getClientSecret()).getBytes(StandardCharsets.UTF_8));
                }
                jweDecrypter.setKeyEncryptionAlgorithm(keyEncryptionAlgorithm);
                jweDecrypter.setBlockEncryptionAlgorithm(blockEncryptionAlgorithm);

                Jwe jwe = jweDecrypter.decrypt(encodedJwt);

                loadHeader(jwe.getHeader().toJsonString());
                loadPayload(jwe.getClaims().toJsonString());
            } else if (parts.length == 2 || parts.length == 3) {
                String encodedHeader = parts[0];
                String encodedClaim = parts[1];
                String encodedSignature = StringUtils.EMPTY;
                if (parts.length == 3) {
                    encodedSignature = parts[2];
                }

                String signingInput = encodedHeader + "." + encodedClaim;
                String header = new String(Base64Util.base64urldecode(encodedHeader), StandardCharsets.UTF_8);
                String payload = new String(Base64Util.base64urldecode(encodedClaim), StandardCharsets.UTF_8);
                payload = payload.replace("\\", "");

                loadHeader(header);

                SignatureAlgorithm sigAlg = SignatureAlgorithm.fromString(algorithm);
                if (sigAlg == null) {
                    throw new InvalidJwtException("The JWT algorithm is not supported");
                }
                if (sigAlg == SignatureAlgorithm.NONE && appConfiguration.getFapiCompatibility()) {
                    throw new InvalidJwtException("None algorithm is not allowed for FAPI");
                }
                if (!validateSignature(cryptoProvider, sigAlg, client, signingInput, encodedSignature)) {
                    throw new InvalidJwtException("The JWT signature is not valid");
                }

                loadPayload(payload);
            } else {
                throw new InvalidJwtException("The JWT is not well formed");
            }

        } catch (Exception e) {
            throw new InvalidJwtException(e);
        }
    }

    public String getEncodedJwt() {
        return encodedJwt;
    }

    private void loadHeader(String header) throws JSONException {
        JSONObject jsonHeader = new JSONObject(header);

        if (jsonHeader.has("typ")) {
            type = jsonHeader.getString("typ");
        }
        if (jsonHeader.has("alg")) {
            algorithm = jsonHeader.getString("alg");
        }
        if (jsonHeader.has("enc")) {
            encryptionAlgorithm = jsonHeader.getString("enc");
        }
        if (jsonHeader.has("kid")) {
            keyId = jsonHeader.getString("kid");
        }
    }

    private void loadPayload(String payload) throws JSONException, UnsupportedEncodingException {
        this.payload = payload;

        JSONObject jsonPayload = new JSONObject(payload);

        if (jsonPayload.has("response_type")) {
            JSONArray responseTypeJsonArray = jsonPayload.optJSONArray("response_type");
            if (responseTypeJsonArray != null) {
                for (int i = 0; i < responseTypeJsonArray.length(); i++) {
                    ResponseType responseType = ResponseType.fromString(responseTypeJsonArray.getString(i));
                    responseTypes.add(responseType);
                }
            } else {
                responseTypes.addAll(ResponseType.fromString(jsonPayload.getString("response_type"), " "));
            }
        }
        if (jsonPayload.has("exp")) {
            exp = jsonPayload.getInt("exp");
        }
        if (jsonPayload.has("aud")) {
            final String audStr = jsonPayload.optString("aud");
            if (StringUtils.isNotBlank(audStr)) {
                this.aud.add(audStr);
            }
            final JSONArray audArray = jsonPayload.optJSONArray("aud");
            if (audArray != null && audArray.length() > 0) {
                this.aud.addAll(Util.asList(audArray));
            }
        }
        clientId = jsonPayload.optString("client_id", null);
        if (jsonPayload.has("scope")) {
            JSONArray scopesJsonArray = jsonPayload.optJSONArray("scope");
            if (scopesJsonArray != null) {
                for (int i = 0; i < scopesJsonArray.length(); i++) {
                    String scope = scopesJsonArray.getString(i);
                    scopes.add(scope);
                }
            } else {
                String scopeStringList = jsonPayload.getString("scope");
                scopes.addAll(Util.splittedStringAsList(scopeStringList, " "));
            }
        }
        if (jsonPayload.has("redirect_uri")) {
            redirectUri = URLDecoder.decode(jsonPayload.getString("redirect_uri"), "UTF-8");
        }
        nonce = jsonPayload.optString("nonce", null);
        state = jsonPayload.optString("state", null);
        if (jsonPayload.has("display")) {
            display = Display.fromString(jsonPayload.getString("display"));
        }
        if (jsonPayload.has("prompt")) {
            JSONArray promptJsonArray = jsonPayload.optJSONArray("prompt");
            if (promptJsonArray != null) {
                for (int i = 0; i < promptJsonArray.length(); i++) {
                    Prompt prompt = Prompt.fromString(promptJsonArray.getString(i));
                    prompts.add(prompt);
                }
            } else {
                prompts.addAll(Prompt.fromString(jsonPayload.getString("prompt"), " "));
            }
        }
        if (jsonPayload.has("claims")) {
            JSONObject claimsJsonObject = jsonPayload.getJSONObject("claims");

            if (claimsJsonObject.has("userinfo")) {
                userInfoMember = new UserInfoMember(claimsJsonObject.getJSONObject("userinfo"));
            }
            if (claimsJsonObject.has("id_token")) {
                idTokenMember = new IdTokenMember(claimsJsonObject.getJSONObject("id_token"));
            }
        }
        iss = jsonPayload.optString("iss", null);
        if (jsonPayload.has("exp")) {
            exp = jsonPayload.getInt("exp");
        }
        if (jsonPayload.has("iat")) {
            iat = jsonPayload.getInt("iat");
        }
        if (jsonPayload.has("nbf")) {
            nbf = jsonPayload.getInt("nbf");
        }
        jti = jsonPayload.optString("jti", null);
        clientNotificationToken = jsonPayload.optString("client_notification_token", null);
        acrValues = jsonPayload.optString("acr_values", null);
        loginHintToken = jsonPayload.optString("login_hint_token", null);
        idTokenHint = jsonPayload.optString("id_token_hint", null);
        loginHint = jsonPayload.optString("login_hint", null);
        bindingMessage = jsonPayload.optString("binding_message", null);
        userCode = jsonPayload.optString("user_code", null);
        codeChallenge = jsonPayload.optString("code_challenge", null);
        codeChallengeMethod = jsonPayload.optString("code_challenge_method", null);

        if (jsonPayload.has("requested_expiry")) {
            // requested_expirity is an exception, it could be String or Number.
            if (jsonPayload.get("requested_expiry") instanceof Number) {
                requestedExpiry = jsonPayload.getInt("requested_expiry");
            } else {
                requestedExpiry = Integer.parseInt(jsonPayload.getString("requested_expiry"));
            }
        }
        if (jsonPayload.has("response_mode")) {
            responseMode = ResponseMode.getByValue(jsonPayload.optString("response_mode"));
        }
    }

    private boolean validateSignature(AbstractCryptoProvider cryptoProvider, SignatureAlgorithm signatureAlgorithm, Client client, String signingInput, String signature) throws Exception {
        ClientService clientService = CdiUtil.bean(ClientService.class);
        String sharedSecret = clientService.decryptSecret(client.getClientSecret());
        JSONObject jwks = Strings.isNullOrEmpty(client.getJwks()) ?
                JwtUtil.getJSONWebKeys(client.getJwksUri()) :
                new JSONObject(client.getJwks());
        return cryptoProvider.verifySignature(signingInput, signature, keyId, jwks, sharedSecret, signatureAlgorithm);
    }

    public String getEncryptionAlgorithm() {
        return encryptionAlgorithm;
    }

    public String getKeyId() {
        return keyId;
    }

    public String getType() {
        return type;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public List<ResponseType> getResponseTypes() {
        return responseTypes;
    }

    public String getClientId() {
        return clientId;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public String getNonce() {
        return nonce;
    }

    public String getState() {
        return state;
    }

    public Display getDisplay() {
        return display;
    }

    public List<Prompt> getPrompts() {
        return prompts;
    }

    public UserInfoMember getUserInfoMember() {
        return userInfoMember;
    }

    public IdTokenMember getIdTokenMember() {
        return idTokenMember;
    }

    public Integer getExp() {
        return exp;
    }

    public List<String> getAud() {
        if (aud == null) aud = Lists.newArrayList();
        return aud;
    }

    public String getPayload() {
        return payload;
    }

    public String getIss() {
        return iss;
    }

    public Integer getIat() {
        return iat;
    }

    public Integer getNbf() {
        return nbf;
    }

    public String getJti() {
        return jti;
    }

    public String getClientNotificationToken() {
        return clientNotificationToken;
    }

    public String getAcrValues() {
        return acrValues;
    }

    public String getLoginHintToken() {
        return loginHintToken;
    }

    public String getIdTokenHint() {
        return idTokenHint;
    }

    public String getLoginHint() {
        return loginHint;
    }

    public String getBindingMessage() {
        return bindingMessage;
    }

    public String getUserCode() {
        return userCode;
    }

    public Integer getRequestedExpiry() {
        return requestedExpiry;
    }

    public ResponseMode getResponseMode() {
        return responseMode;
    }

    public String getCodeChallenge() {
        return codeChallenge;
    }

    public String getCodeChallengeMethod() {
        return codeChallengeMethod;
    }

    @Nullable
    private static String queryRequest(@Nullable String requestUri, @Nullable RedirectUriResponse redirectUriResponse,
                                       AppConfiguration appConfiguration) {
        if (StringUtils.isBlank(requestUri)) {
            return null;
        }
        boolean validRequestUri = false;
        try {
            URI reqUri = new URI(requestUri);
            String reqUriHash = reqUri.getFragment();
            String reqUriWithoutFragment = reqUri.getScheme() + ":" + reqUri.getSchemeSpecificPart();

            ClientRequest clientRequest = new ClientRequest(reqUriWithoutFragment);
            clientRequest.setHttpMethod(HttpMethod.GET);

            ClientResponse<String> clientResponse = clientRequest.get(String.class);
            int status = clientResponse.getStatus();

            String request = null;
            if (status == 200) {
                request = clientResponse.getEntity(String.class);

                if (StringUtils.isBlank(reqUriHash) || !appConfiguration.getRequestUriHashVerificationEnabled()) {
                    validRequestUri = true;
                } else {
                    String hash = Base64Util.base64urlencode(JwtUtil.getMessageDigestSHA256(request));
                    validRequestUri = StringUtils.equals(reqUriHash, hash);
                }
            }

            if (!validRequestUri && redirectUriResponse != null) {
                throw redirectUriResponse.createWebException(AuthorizeErrorResponseType.INVALID_REQUEST_URI, "Invalid request uri.");
            }
            return request;
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return null;
        }
    }

    public static JwtAuthorizationRequest createJwtRequest(String request, String requestUri, Client client, RedirectUriResponse redirectUriResponse, AbstractCryptoProvider cryptoProvider, AppConfiguration appConfiguration) {
        final String requestFromClient = queryRequest(requestUri, redirectUriResponse, appConfiguration);
        if (StringUtils.isNotBlank(requestFromClient)) {
            request = requestFromClient;
        }

        if (StringUtils.isBlank(request)) {
            return null;
        }

        try {
            return new JwtAuthorizationRequest(appConfiguration, cryptoProvider, request, client);
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Invalid JWT authorization request. " + e.getMessage(), e);
        }
        return null;
    }

}