package com.tcms.auth;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * GitHub's half of the social flow — the one provider of the three that is not OIDC: no
 * discovery, no {@code id_token}, no nonce. Identity is the answer of {@code GET /user} made
 * with the token the code exchange yields, and the subject is the account's immutable numeric
 * {@code id} — never {@code login}, which is a rename away from binding somebody else.
 *
 * <p>Protocol only, per §6r trap 5: this class maps HTTP to a record and decides nothing.
 * Every refusal is {@link Optional#empty()}; the caller has one refusal for everything.
 *
 * <p>Uses the shared {@code oidcRestClient} bean so both timeouts bound these calls too —
 * github.com is an external host a request thread blocks on, exactly like an issuer.
 */
@Component
public class GithubUserClient {

    private static final Logger log = LoggerFactory.getLogger(GithubUserClient.class);

    static final String AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
    private static final String WEB_BASE = "https://github.com";
    private static final String API_BASE = "https://api.github.com";

    /** What GitHub asserted. Email is best-effort provenance (the plan's decision 13). */
    public record GithubIdentity(String subject, String name, String email, boolean emailVerified) {
    }

    private final RestClient rest;
    private final String webBase;
    private final String apiBase;

    @Autowired
    GithubUserClient(RestClient http) {
        this(http, WEB_BASE, API_BASE);
    }

    /** Test seam: the two hosts are GitHub's in production and a loopback stub under test. */
    GithubUserClient(RestClient http, String webBase, String apiBase) {
        this.rest = http;
        this.webBase = webBase;
        this.apiBase = apiBase;
    }

    public Optional<GithubIdentity> identityFor(
            String clientId, String clientSecret, String code, String redirectUri) {

        return exchange(clientId, clientSecret, code, redirectUri).flatMap(this::fetchUser);
    }

    private Optional<String> exchange(
            String clientId, String clientSecret, String code, String redirectUri) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            form.add("code", code);
            form.add("redirect_uri", redirectUri);

            Map<?, ?> body = rest.post()
                    .uri(webBase + "/login/oauth/access_token")
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            // GitHub reports failure as HTTP 200 with an "error" member, so the status alone
            // proves nothing — the token's presence is the success signal.
            Object accessToken = body == null ? null : body.get("access_token");
            if (accessToken instanceof String value && !value.isBlank()) {
                return Optional.of(value);
            }
            log.info("GitHub token exchange refused: {}",
                    body == null ? "empty body" : body.get("error"));
            return Optional.empty();
        } catch (RestClientException refused) {
            log.info("GitHub token exchange failed: {}", refused.getMessage());
            return Optional.empty();
        }
    }

    private Optional<GithubIdentity> fetchUser(String accessToken) {
        Map<?, ?> user;
        try {
            user = rest.get()
                    .uri(apiBase + "/user")
                    .header("Authorization", "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException refused) {
            log.info("GitHub /user read failed: {}", refused.getMessage());
            return Optional.empty();
        }
        Object id = user == null ? null : user.get("id");
        if (id == null) {
            log.info("GitHub /user answered without an id; refusing");
            return Optional.empty();
        }
        String name = user.get("name") instanceof String text && !text.isBlank()
                ? text
                : user.get("login") instanceof String login ? login : null;

        VerifiedEmail email = primaryEmail(accessToken);
        return Optional.of(new GithubIdentity(
                String.valueOf(id), name, email.address(), email.verified()));
    }

    private record VerifiedEmail(String address, boolean verified) {
    }

    /** Best-effort: a refusal here (scope withheld, API hiccup) degrades to "no email". */
    private VerifiedEmail primaryEmail(String accessToken) {
        try {
            List<?> emails = rest.get()
                    .uri(apiBase + "/user/emails")
                    .header("Authorization", "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(List.class);
            if (emails != null) {
                for (Object entry : emails) {
                    if (entry instanceof Map<?, ?> row
                            && Boolean.TRUE.equals(row.get("primary"))
                            && row.get("email") instanceof String address) {
                        return new VerifiedEmail(address, Boolean.TRUE.equals(row.get("verified")));
                    }
                }
            }
        } catch (RestClientException ignored) {
            log.info("GitHub /user/emails read failed; proceeding without an email");
        }
        return new VerifiedEmail(null, false);
    }
}
