package com.tcms.integration;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * One JSON POST to somebody else's server — the half {@link SlackWebhookAdapter} and
 * {@link TeamsWebhookAdapter} have in common.
 *
 * <h2>The timeouts are the reason this is a bean and not two lines in each adapter</h2>
 *
 * <p>This call runs on the outbox dispatcher's thread, and that thread's next job is every other
 * company's events. A webhook host that accepts the connection and then never answers would hold
 * the whole loop for as long as the platform's default read timeout allows, which is
 * <em>indefinitely</em> on the JDK's client. So both bounds are set here, once, rather than being
 * a thing each adapter remembers.
 *
 * <p>They are short on purpose. A notification that took thirty seconds to send is one the outbox
 * should have retried instead: the row stays {@code PENDING}, the backoff applies, and nothing is
 * lost — which is exactly what the outbox is for and is a better outcome than a dispatcher tick
 * that never finishes.
 *
 * <h2>A non-2xx is a failure and is allowed to throw</h2>
 *
 * <h2>The URL guard, in WARN mode — 004 C5</h2>
 *
 * <p>{@link OutboundUrlGuard} refuses {@code http}, and refuses a destination that resolves inside
 * this network. Both are things some existing channel almost certainly does: a webhook to a
 * self-hosted Mattermost on a private LAN is an ordinary configuration, and it was configured
 * against no rule at all, because the guard did not exist.
 *
 * <p><strong>So the refusal is counted and logged, and the post proceeds.</strong> Turning it on
 * retroactively would break deliveries that work, at the moment of an upgrade, with no screen saying
 * why — and the person who would have to fix it is not the person doing the upgrade. Enforcement on
 * existing channels is its own later commit and it needs the channel-health warning on the
 * notification settings page to land first, so an operator can see which channels are about to stop
 * before they do.
 *
 * <p>The counter is the point of the warning rather than the log line: a log nobody greps says
 * nothing about whether enforcement is safe to turn on, and {@link #refusalCount()} is what a test —
 * and later a health endpoint — can read. {@code TrackerClient} enforces the same guard, because
 * nothing there predates it.
 *
 * <p>{@code RestClient}'s default status handler raises on 4xx and 5xx, and that default is kept:
 * Slack answers {@code 404 no_service} for a revoked webhook and {@code 400 invalid_payload} for a
 * document it will not take, and both are things an operator has to see in the delivery log rather
 * than facts the product should decide are fine.
 */
@Component
class WebhookPoster {

    /** Long enough for a TLS handshake across an ocean, short enough not to be a stall. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** See the class header: a slow webhook is the outbox's problem, not this thread's. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(WebhookPoster.class);

    private final RestClient client;
    private final OutboundUrlGuard guard;

    /**
     * How many posts have gone to a destination the guard would refuse, in this process.
     *
     * <p>In-memory and per process, like the rate limiter's counters and for the same reason: TCMS
     * is one deployable. It answers one question — <em>would enforcing this break anything here</em>
     * — and a number that resets on restart still answers it.
     */
    private final AtomicInteger refusals = new AtomicInteger();

    WebhookPoster(RestClient.Builder builder, OutboundUrlGuard guard) {
        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.detect()
                .build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT));
        this.client = builder.requestFactory(factory).build();
        this.guard = guard;
    }

    /** See the class header: what a later slice reads before turning enforcement on. */
    int refusalCount() {
        return refusals.get();
    }

    /**
     * @param url  the incoming-webhook URL, which <strong>is</strong> the credential — never log it
     * @param body a complete JSON document, already the shape the receiving service expects
     */
    void post(String url, String body) {
        OutboundUrlGuard.Verdict verdict = guard.check(url);
        if (verdict != OutboundUrlGuard.Verdict.ALLOWED) {
            refusals.incrementAndGet();
            // The URL is the credential, so the message names the VERDICT and not the destination.
            // A log line quoting the webhook would be the leak SecretRedaction exists to prevent,
            // arriving through the one path that was trying to be helpful about it.
            log.warn("A notification channel posts to a destination this instance would refuse "
                    + "under tracker rules ({}). Delivery proceeded — see WebhookPoster for why "
                    + "this is a warning and not a refusal.", verdict);
        }
        client.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
