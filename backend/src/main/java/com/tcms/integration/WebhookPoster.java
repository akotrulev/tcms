package com.tcms.integration;

import java.time.Duration;
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

    private final RestClient client;

    WebhookPoster(RestClient.Builder builder) {
        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.detect()
                .build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT));
        this.client = builder.requestFactory(factory).build();
    }

    /**
     * @param url  the incoming-webhook URL, which <strong>is</strong> the credential — never log it
     * @param body a complete JSON document, already the shape the receiving service expects
     */
    void post(String url, String body) {
        client.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
