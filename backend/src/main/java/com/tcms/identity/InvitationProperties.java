package com.tcms.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How invitations are addressed and how long they last.
 *
 * <p>Typed and validated rather than {@code @Value} strings read at three call sites: a
 * malformed base URL produces links that 404 for every invitee, and the cheapest place to
 * discover that is startup.
 *
 * @param baseUrl  the SPA's public origin — what an invitee's browser can actually reach.
 *                 Not derivable from the request: the invitation link is built by the
 *                 provisioning CLI, which has no request, and behind Caddy the container
 *                 only ever sees {@code localhost:8080}.
 * @param validity how long a link stays good. Long enough to survive a weekend and a spam
 *                 folder, short enough that a forwarded mail is not a standing key to a
 *                 client's company. Re-running {@code provision-company} after expiry
 *                 issues a fresh one, which is why a short window is not a trap.
 */
@ConfigurationProperties(prefix = "tcms.invitation")
@Validated
public record InvitationProperties(@NotNull URI baseUrl, @NotNull Duration validity,
                                   @NotBlank String acceptPath) {

    /**
     * The URL to put in front of the invitee.
     *
     * <p>The secret travels in the <strong>query string</strong> rather than the path
     * because the SPA route that consumes it does not exist yet (it is frontend work, and
     * frontend M6 owns the invitation surface) — a query parameter is readable by whatever
     * page eventually handles it without pinning a path shape now.
     *
     * <p><strong>The token in a URL is a known exposure</strong> and it is accepted with
     * its mitigations rather than pretended away: URLs land in browser history, in
     * {@code Referer} headers on outbound links, and in any proxy log that records query
     * strings. What bounds it is that the token is single-use, short-lived, and useless
     * once redeemed — which is exactly why redemption sets {@code consumed_at} rather than
     * relying on expiry alone.
     */
    public String acceptUrl(String secret) {
        String origin = baseUrl.toString().replaceAll("/+$", "");
        return origin + acceptPath + "?token=" + secret;
    }
}
