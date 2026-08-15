package com.tcms.auth;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Fetches and caches an issuer's {@code openid-configuration}.
 *
 * <p>Cached <strong>per issuer rather than per provider</strong>: two companies may point at the
 * same Keycloak realm, and the metadata is a property of the issuer, not of who configured it. The
 * cache is unbounded in the way a per-issuer cache can afford to be — its key space is the set of
 * distinct {@code issuer_uri} values an administrator has typed, which is bounded by the provider
 * table.
 *
 * <p>An entry is kept for {@link OidcProperties#discoveryTtl()} and then re-fetched. The stale
 * entry is <em>not</em> served when the re-fetch fails: an issuer that has rotated its
 * {@code jwks_uri} and gone briefly unreachable would otherwise have signatures validated against
 * keys it has retired.
 */
@Component
public class OidcDiscovery {

    private static final Logger log = LoggerFactory.getLogger(OidcDiscovery.class);

    private static final String WELL_KNOWN = "/.well-known/openid-configuration";

    private record Cached(OidcDiscoveryDocument document, Instant staleAt) {
    }

    private final ConcurrentMap<String, Cached> cache = new ConcurrentHashMap<>();
    private final RestClient http;
    private final OidcProperties properties;
    private final Clock clock;

    public OidcDiscovery(RestClient http, OidcProperties properties, Clock clock) {
        this.http = http;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The issuer's metadata, or empty when it is unreachable, malformed, or claims to be somebody
     * else.
     *
     * @param issuerUri the configured issuer, which must be {@code https} — an {@code http} issuer
     *                  puts the discovery document, and therefore the {@code jwks_uri} every
     *                  signature is checked against, in the hands of anyone on the path
     */
    public Optional<OidcDiscoveryDocument> forIssuer(String issuerUri) {
        if (issuerUri == null || !issuerUri.startsWith("https://")) {
            return Optional.empty();
        }
        String normalised = issuerUri.endsWith("/")
                ? issuerUri.substring(0, issuerUri.length() - 1)
                : issuerUri;

        Cached cached = cache.get(normalised);
        if (cached != null && clock.instant().isBefore(cached.staleAt())) {
            return Optional.of(cached.document());
        }
        return fetch(normalised).map(document -> {
            cache.put(normalised, new Cached(document, clock.instant().plus(properties.discoveryTtl())));
            return document;
        });
    }

    private Optional<OidcDiscoveryDocument> fetch(String issuerUri) {
        try {
            OidcDiscoveryDocument document = http.get()
                    .uri(URI.create(issuerUri + WELL_KNOWN))
                    .retrieve()
                    .body(OidcDiscoveryDocument.class);

            if (document == null || !document.isComplete()) {
                log.warn("Discovery for {} answered without the fields the flow needs", issuerUri);
                return Optional.empty();
            }
            if (!issuerUri.equals(trimmed(document.issuer()))) {
                // The document names an issuer other than the one it was fetched from. Refused
                // rather than followed: `iss` on every id_token is validated against this value,
                // so accepting the document's own claim here would let a compromised or
                // mistyped host nominate the identity it is trusted as.
                log.warn("Discovery for {} declared issuer {}", issuerUri, document.issuer());
                return Optional.empty();
            }
            return Optional.of(document);
        } catch (RestClientException unreachable) {
            log.warn("Discovery for {} failed: {}", issuerUri, unreachable.getMessage());
            return Optional.empty();
        }
    }

    private static String trimmed(String issuer) {
        return issuer != null && issuer.endsWith("/")
                ? issuer.substring(0, issuer.length() - 1)
                : issuer;
    }
}
