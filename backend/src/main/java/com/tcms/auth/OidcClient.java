package com.tcms.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The back-channel half of the authorization-code flow: a code goes out, an {@code id_token} comes
 * back, and the browser is not involved in either.
 *
 * <p>The client secret travels in the {@code Authorization} header ({@code client_secret_basic})
 * rather than in the form body. Both are permitted by the specification and the difference is where
 * the value ends up: a body is what a proxy or an issuer is most likely to log, and this is the one
 * request in the product that carries a long-lived shared secret.
 *
 * <p>A provider with no stored secret is a <strong>public client</strong> and sends none — PKCE is
 * what proves the exchange belongs to the browser that started it, and the verifier is sent either
 * way.
 */
@Component
public class OidcClient {

    private static final Logger log = LoggerFactory.getLogger(OidcClient.class);

    private final RestClient http;

    public OidcClient(RestClient http) {
        this.http = http;
    }

    /**
     * Exchanges an authorization code.
     *
     * @param clientSecret the plaintext secret, decrypted for the length of this call, or
     *                     {@code null} for a public client. <strong>Never logged, never returned,
     *                     never put in an error message</strong> (§6r)
     * @param redirectUri  must be byte-identical to the one sent on the authorize leg; the issuer
     *                     compares them and so does the attempt row
     * @return the response, or empty when the issuer refused or could not be reached. One refusal,
     *         with the cause left in the log
     */
    public Optional<OidcTokenResponse> exchange(
            String tokenEndpoint,
            String clientId,
            String clientSecret,
            String code,
            String redirectUri,
            String codeVerifier) {

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("code_verifier", codeVerifier);
        form.add("client_id", clientId);

        try {
            OidcTokenResponse response = http.post()
                    .uri(URI.create(tokenEndpoint))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .headers(headers -> basicAuth(headers, clientId, clientSecret))
                    .body(form)
                    .retrieve()
                    .body(OidcTokenResponse.class);

            if (response == null || response.idToken() == null || response.idToken().isBlank()) {
                log.info("Token exchange at {} answered without an id_token", tokenEndpoint);
                return Optional.empty();
            }
            return Optional.of(response);
        } catch (RestClientException refused) {
            log.info("Token exchange at {} failed: {}", tokenEndpoint, refused.getMessage());
            return Optional.empty();
        }
    }

    private static void basicAuth(HttpHeaders headers, String clientId, String clientSecret) {
        if (clientSecret == null || clientSecret.isBlank()) {
            return;
        }
        // Form-encoded before joining, per RFC 6749 §2.3.1. Identity for the values an issuer
        // usually generates, and required for the ones that carry a `+` or a `/`.
        String credential = URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + ":" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        headers.set(HttpHeaders.AUTHORIZATION, "Basic "
                + Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8)));
    }
}
