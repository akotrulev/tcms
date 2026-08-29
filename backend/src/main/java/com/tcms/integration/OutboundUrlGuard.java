package com.tcms.integration;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Decides whether this server may connect to a URL somebody typed into a form.
 *
 * <h2>The attack this exists for, stated once</h2>
 *
 * <p>{@code tracker_binding.base_url} is operator input, and under 004 D2 the server carries
 * <strong>every connected user's</strong> credential when it goes there. So a base URL that
 * resolves somewhere unintended does not exfiltrate one bot's token — it exfiltrates a token per
 * person, to whatever answered. That is the whole reason this class is a shared component in
 * {@code integration} rather than three lines inside the tracker client (D10).
 *
 * <h2>Resolution happens, and it is the point</h2>
 *
 * <p>A string check sees {@code https://metadata.internal.example} and finds nothing wrong with it.
 * The address it resolves to is {@code 169.254.169.254}, which is a cloud instance's credential
 * endpoint. <strong>So the destination is refused after DNS, not before</strong>, and every private
 * range goes with it: loopback, link-local, site-local, unique-local and the wildcard address.
 *
 * <p>This is not a complete SSRF defence and does not claim to be. DNS can answer differently
 * between this check and the connection that follows it (the "rebinding" window), and closing that
 * needs a custom socket factory that pins the resolved address — a real piece of work this class
 * deliberately does not fake having done. What it does close is the ordinary case, which is the one
 * that arrives through a settings form.
 *
 * <h2>Redirects are not followed, and that is enforced by the caller</h2>
 *
 * <p>A 3xx from a tracker is a misconfiguration, and following one relocates the credential to
 * whatever the redirect names — past this guard, which only ever saw the first URL.
 * {@link TrackerClient} configures its request factory not to follow them; this class's job is the
 * URL it is given, and {@link #check} is called again on every hop a caller decides to make.
 *
 * <h2>Two callers, two modes, and the difference is temporary</h2>
 *
 * <ul>
 *   <li>{@link TrackerClient} <strong>enforces</strong>: a refused destination is never dialled.</li>
 *   <li>{@link WebhookPoster} <strong>warns</strong>: the surface predates this guard, existing
 *       channels were configured against no rule at all, and turning one on retroactively would
 *       break deliveries that work, at the moment of an upgrade, with no screen saying why. So the
 *       refusal is counted and logged and the post proceeds — plan 004 C5, warn-then-enforce.
 *       Enforcement on existing channels is its own later commit, and it needs the channel-health
 *       warning on the notification settings page to land first.</li>
 * </ul>
 */
@Component
public class OutboundUrlGuard {

    /** Why a destination was refused, or {@link #ALLOWED}. One value per rule, so a caller can say which. */
    public enum Verdict {

        /** Dial it. */
        ALLOWED,

        /** Not a URL at all, or one with no host. */
        MALFORMED,

        /**
         * {@code http}, {@code file}, {@code gopher}, anything but {@code https}.
         *
         * <p>Plain {@code http} is refused rather than upgraded: a credential sent in cleartext is
         * already spent by the time a redirect to TLS arrives.
         */
        NOT_HTTPS,

        /** The name does not resolve. Refused rather than deferred — the dial would fail anyway. */
        UNRESOLVABLE,

        /**
         * It resolves inside this network. The interesting member: {@code 169.254.169.254} is a
         * credential endpoint, {@code 10.x} is somebody's intranet, and {@code 127.0.0.1} is this
         * process. Allowed only when {@code tcms.integration.outbound.allow-private-hosts} is on.
         */
        PRIVATE_DESTINATION
    }

    private final OutboundProperties properties;

    /**
     * Public so the guard can be constructed directly in a test, which is the only way its matrix
     * can be driven against both settings of {@code allow-private-hosts} in one run.
     */
    public OutboundUrlGuard(OutboundProperties properties) {
        this.properties = properties;
    }

    /**
     * @param url what somebody typed, or what a caller is about to dial
     * @return {@link Verdict#ALLOWED}, or the first rule that refused — never a boolean, because
     *         both callers put the reason on a screen and "false" is not a sentence
     */
    public Verdict check(String url) {
        if (url == null || url.isBlank()) {
            return Verdict.MALFORMED;
        }
        URI parsed;
        try {
            parsed = new URI(url.trim());
        } catch (URISyntaxException malformed) {
            return Verdict.MALFORMED;
        }
        String scheme = parsed.getScheme() == null
                ? null
                : parsed.getScheme().toLowerCase(Locale.ROOT);
        String host = parsed.getHost();
        if (host == null || host.isBlank()) {
            // A URI with an underscore in the authority, or a bare path, parses and has no host.
            // Refused here rather than at the scheme check so `http://` still reports NOT_HTTPS,
            // which is the more useful sentence for the person who typed it.
            return "https".equals(scheme) ? Verdict.MALFORMED : Verdict.NOT_HTTPS;
        }
        if (!"https".equals(scheme)) {
            return Verdict.NOT_HTTPS;
        }
        if (properties.allowPrivateHosts()) {
            // The operator has said this instance talks to a private tracker. Resolution is skipped
            // entirely rather than resolved-and-permitted: the check has nothing left to decide,
            // and a DNS round trip per call for an answer that cannot change the outcome is a
            // latency budget spent on nothing.
            return Verdict.ALLOWED;
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException unresolvable) {
            return Verdict.UNRESOLVABLE;
        }
        for (InetAddress address : addresses) {
            // EVERY address, not the first. A name that answers with a public address and a private
            // one is the cheapest way past a check that looks at one of them, and which one comes
            // back first is not ours to decide.
            if (isPrivate(address)) {
                return Verdict.PRIVATE_DESTINATION;
            }
        }
        return Verdict.ALLOWED;
    }


    /**
     * Everything that is not somebody else's public server.
     *
     * <p>{@code isSiteLocalAddress} covers {@code 10/8}, {@code 172.16/12} and {@code 192.168/16};
     * {@code isLinkLocalAddress} covers {@code 169.254/16} and IPv6 {@code fe80::/10};
     * {@code isLoopbackAddress} covers {@code 127/8} and {@code ::1}; {@code isAnyLocalAddress}
     * covers {@code 0.0.0.0}. {@code isSiteLocalAddress} is <strong>false</strong> for IPv6
     * unique-local ({@code fc00::/7}) — the JDK's method predates it — so that range is tested
     * against its own prefix rather than assumed covered.
     */
    private static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] octets = address.getAddress();
        if (octets.length == 16) {
            return (octets[0] & 0xFE) == 0xFC;
        }
        // 100.64/10 — carrier-grade NAT, which the JDK calls public and which reaches a network
        // this deployment did not choose to be on.
        return (octets[0] & 0xFF) == 100 && (octets[1] & 0xC0) == 0x40;
    }
}
