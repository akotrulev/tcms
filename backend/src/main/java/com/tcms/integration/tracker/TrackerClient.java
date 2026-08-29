package com.tcms.integration.tracker;

import com.tcms.integration.OutboundUrlGuard;
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
 * The one {@code RestClient} every tracker adapter calls through — timeouts, the URL guard, a
 * response cap, and no redirects.
 *
 * <h2>Read {@code WebhookPoster}'s class header first; then read this paragraph, which is the
 * difference</h2>
 *
 * <p>{@code WebhookPoster} sets both timeouts because its call runs on the <strong>outbox
 * dispatcher's thread</strong>, whose next job is every other company's events, and the JDK client's
 * default read timeout is indefinite. Same defect, different blast radius here:
 * <strong>this call runs on a request thread</strong>. A tracker that accepts the connection and
 * never answers is not a stalled dispatcher, it is a stalled page — one Tomcat worker held per
 * viewer looking at a case with an issue chip on it, for as long as the platform allows. There is no
 * outbox to retry into and no backoff to fall back on, so <strong>the read timeout is the whole
 * defence</strong>, and it is why this is a bean rather than two lines in each of three adapters.
 *
 * <h2>Redirects are not followed</h2>
 *
 * <p>A 3xx from a tracker is a misconfiguration. Following one relocates <em>somebody's personal
 * credential</em> to whatever the redirect names — past {@link OutboundUrlGuard}, which only ever saw
 * the URL we chose to dial. {@code DONT_FOLLOW} makes that a failed call with a status a person can
 * read instead.
 *
 * <h2>The guard runs here as well as at the write</h2>
 *
 * <p>A binding's {@code base_url} is checked when it is stored, and again on every call. The second
 * check is not belt-and-braces: DNS can start answering differently after the row was written, and
 * the row can predate the guard. Enforced here, warned about in {@code WebhookPoster} — 004 C5.
 */
@Component
public class TrackerClient {

    /** Long enough for a TLS handshake across an ocean, short enough not to be a stall. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** See the class header: a hung tracker is a hung page, and this is the whole defence. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 1 MiB, and it is a bound on somebody else's server rather than on our parsing.
     *
     * <p>A tracker that answers a search with fifty megabytes of JSON — because a query matched
     * everything, or because the host is not the tracker at all — would be read entirely into this
     * process's heap before anything got to reject it. The cap is applied by reading the body as a
     * byte array and refusing an over-long one, because {@code RestClient} has no size setting.
     */
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final RestClient client;
    private final OutboundUrlGuard guard;

    @Autowired
    TrackerClient(RestClient.Builder builder, OutboundUrlGuard guard) {
        this(builder.requestFactory(transport()).build(), guard);
    }

    /**
     * The transport, already built — and the only way {@code MockRestServiceServer} can be bound.
     *
     * <p>It binds by installing its own {@code ClientHttpRequestFactory} on a
     * {@code RestClient.Builder}, so the constructor above — which installs the real one over the
     * top — silently un-bound it. That is not a cosmetic test problem: the transport suite was
     * dialling {@code acme.atlassian.net} over the public internet on every run, and
     * {@code *.atlassian.net} resolves, so the failures read as parse errors and a real Jira
     * {@code 400} rather than as "this never reached the double".
     *
     * <p>Package-private and second, with {@code @Autowired} on the one above, because two
     * candidate constructors is a context that will not start.
     */
    TrackerClient(RestClient client, OutboundUrlGuard guard) {
        this.client = client;
        this.guard = guard;
    }

    /** Timeouts and no redirects — the class header argues both. */
    private static ClientHttpRequestFactory transport() {
        return ClientHttpRequestFactoryBuilder.detect()
                .build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT)
                        .withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW));
    }

    /**
     * The builder the adapters compose their one call on, after the destination has been vetted.
     *
     * <p><strong>The guard is applied to {@code url} and not to the base URL</strong>, because the
     * adapter is what knows the whole address it is about to dial — a base URL that passes and a
     * path that redirects the request elsewhere is a distinction only the caller can collapse.
     *
     * @throws TrackerUnavailableException when the guard refuses, so a refused destination reaches
     *         the caller as the same state every other tracker failure does (D16) and never as a
     *         stack trace naming an internal address
     */
    public <T> T exchange(String url, Function<RestClient, T> call) {
        OutboundUrlGuard.Verdict verdict = guard.check(url);
        if (verdict != OutboundUrlGuard.Verdict.ALLOWED) {
            throw new TrackerUnavailableException(
                    "This instance will not connect to " + hostOf(url) + " (" + verdict + ").");
        }
        try {
            return call.apply(client);
        } catch (RestClientException failure) {
            // Wrapped rather than propagated: every caller renders one state, and a RestClientException
            // message quotes the URL it was calling — which for these requests carries no credential
            // (they travel in a header) and can still carry a redirect target that does.
            throw new TrackerUnavailableException(
                    com.tcms.integration.SecretRedaction.apply(failure.getMessage(), null), failure);
        }
    }

    /** The cap, applied where a body becomes a string. */
    public static String withinCap(byte[] body) {
        if (body == null) {
            return "";
        }
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new TrackerUnavailableException(
                    "The tracker's answer was larger than this product will read.");
        }
        return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Host only, so a refusal message names the site without echoing a path somebody typed. */
    private static String hostOf(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? "that address" : host;
        } catch (IllegalArgumentException malformed) {
            return "that address";
        }
    }
}
