package com.tcms.tracker.imports;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The one {@code RestClient} every {@link ImportSource} calls through — timeouts, the URL guard, a
 * response cap, and no redirects.
 *
 * <p>{@code integration.tracker.TrackerClient} is this class, one module over, and is unreachable
 * (D-a). Its arguments are this class's arguments and are repeated rather than referenced, because
 * a reader of this file cannot be sent to a file this file may not name.
 *
 * <h2>The read runs on a request thread, so the read timeout is the whole defence</h2>
 *
 * <p>A source that accepts the connection and never answers is not a stalled background job, it is
 * a stalled page: one Tomcat worker held per person waiting on a preview, for as long as the
 * platform allows, and the JDK client's default read timeout is <strong>indefinite</strong>. There
 * is no outbox to retry into and no backoff to fall back on. The value is configurable
 * ({@code tcms.tracker.imports.read-timeout}) and larger than the case-page client's, because a
 * hundred-issue page with comments is genuinely slower than a one-issue read.
 *
 * <h2>Redirects are not followed</h2>
 *
 * <p>A 3xx from a tracker is a misconfiguration, and following one relocates the person's API token
 * to whatever the redirect names — past {@link ImportUrlGuard}, which only ever saw the URL we chose
 * to dial. {@code DONT_FOLLOW} makes that a failed call with a status somebody can read instead.
 *
 * <h2>The response cap is a bound on somebody else's server, not on our parsing</h2>
 *
 * <p>A source that answers a page request with fifty megabytes — because the host is not the source
 * at all, or because a query matched everything — would be read whole into this process's heap
 * before anything rejected it. {@code RestClient} has no size setting, so the body is taken as a
 * byte array and an over-long one is refused.
 */
@Component
public class ImportHttp {

    /** Long enough for a TLS handshake across an ocean, short enough not to be a stall. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** 4 MiB — four times the case page's cap, for the same reason the read timeout is longer. */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final RestClient client;
    private final ImportUrlGuard guard;

    @Autowired
    ImportHttp(RestClient.Builder builder, ImportUrlGuard guard, ImportProperties properties) {
        this(builder.requestFactory(transport(properties.readTimeout())).build(), guard);
    }

    /**
     * The transport, already built — and <strong>the only way {@code MockRestServiceServer} can be
     * bound.</strong>
     *
     * <p>It binds by installing its own {@code ClientHttpRequestFactory} on a
     * {@code RestClient.Builder}, so the constructor above — which installs the real one over the
     * top — silently un-binds it. That is not a cosmetic test problem: the equivalent mistake one
     * module over had the tracker transport suite dialling {@code acme.atlassian.net} over the
     * public internet on every run, and because {@code *.atlassian.net} resolves, the failures read
     * as parse errors and a real Jira {@code 400} rather than as "this never reached the double".
     *
     * <p>Package-private and second, with {@code @Autowired} on the one above, because two
     * candidate constructors is a context that will not start.
     */
    ImportHttp(RestClient client, ImportUrlGuard guard) {
        this.client = client;
        this.guard = guard;
    }

    /** Timeouts and no redirects — the class header argues both. */
    private static ClientHttpRequestFactory transport(Duration readTimeout) {
        return ClientHttpRequestFactoryBuilder.detect()
                .build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(readTimeout)
                        .withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW));
    }

    /**
     * The builder a source composes its one call on, after the destination has been vetted.
     *
     * <p><strong>The guard is applied to {@code url} and not to the site URL</strong>, because the
     * source is what knows the whole address it is about to dial — a site that passes and a path
     * that sends the request elsewhere is a distinction only the caller can collapse.
     *
     * @throws ImportUrlRefusedException when the guard refuses, so the refusal reaches the
     *         controller as a {@code 400} the person who typed the address can act on, and never as
     *         a stack trace naming an internal address
     * @throws ImportSourceUnavailableException for every transport failure, so a caller renders one
     *         state
     */
    public <T> T exchange(String url, Function<RestClient, T> call) {
        ImportUrlGuard.Verdict verdict = guard.check(url);
        if (verdict != ImportUrlGuard.Verdict.ALLOWED) {
            throw ImportUrlRefusedException.forUrl(url, verdict);
        }
        try {
            return call.apply(client);
        } catch (RestClientException failure) {
            // Wrapped rather than propagated: a RestClientException's message quotes the URL it was
            // calling, which carries no credential here (they travel in a header) and can still
            // carry a redirect target that does.
            throw new ImportSourceUnavailableException(
                    "The source system did not answer as expected.", failure);
        }
    }

    /** The cap, applied where a body becomes a string. */
    public static String withinCap(byte[] body) {
        if (body == null) {
            return "";
        }
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new ImportSourceUnavailableException(
                    "The source system's answer was larger than this product will read. Import a "
                            + "smaller project, or ask the source's administrator about the page "
                            + "size it is serving.");
        }
        return new String(body, StandardCharsets.UTF_8);
    }
}
