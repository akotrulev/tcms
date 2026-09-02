package com.tcms.tracker.imports;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Decides whether this server may connect to the site somebody typed into the import wizard.
 *
 * <h2>This is a SECOND COPY OF A SECURITY CONTROL, and that is the bad option</h2>
 *
 * <p>{@code com.tcms.integration.OutboundUrlGuard} already does exactly this, for exactly this
 * attack. {@code tracker} may not import {@code integration} —
 * {@code ArchitectureRulesTest.crossModuleDependenciesPointOnlyAtTheFoundation} computes a type's
 * module from its first package segment, so this package <em>is</em> {@code tracker}, and
 * {@code tracker} does not declare {@code integration}. Plan 014 D-c weighs the three ways out and
 * takes this one: promoting the guard to a sixth foundation module fails the bar
 * {@code ArchitectureRules.FOUNDATION_MODULES}' javadoc sets — <em>a foundation module is a
 * decision, not an import</em>, and one class is not that decision — and moving it into
 * {@code api} fails that module's narrower charter, which admits a type only when the
 * <em>contract</em> defines it.
 *
 * <p><strong>So the copy exists and is not allowed to drift.</strong> The rules below sit between
 * two markers and {@code ImportUrlGuardMirrorsTheOutboundGuardTest} reads both files as text and
 * fails when the regions differ. A copy of a security control that may drift is worse than no
 * copy: the private-range list gets a fix in one file and the other keeps dialling
 * {@code 169.254.169.254}.
 *
 * <h2>The attack, restated because this caller is not the other one</h2>
 *
 * <p>{@code ImportConnection.siteUrl} is typed into a form by whoever is running the import, and
 * the server carries <em>their</em> API token there. A site that resolves somewhere unintended
 * does not merely fail — it hands a live Jira credential to whatever answered. Resolution happens
 * after DNS and not before, because {@code https://metadata.internal.example} is a string with
 * nothing wrong with it and an address that is a cloud instance's credential endpoint.
 *
 * <p>Like {@code TrackerClient} and unlike {@code WebhookPoster}, the importer
 * <strong>enforces</strong>: a refused destination is never dialled, and there is no
 * warn-then-enforce phase because there is no installed base of import connections to break.
 */
@Component
public class ImportUrlGuard {

    /**
     * Why a destination was refused, or {@link #ALLOWED}. One value per rule, so the caller can
     * say which.
     *
     * <p>Deliberately outside the mirrored region below: the values and their order are the same
     * as {@code integration}'s by construction — the mirrored code returns them by name and would
     * not compile against a different set — while the prose has to name <em>this</em> module's
     * property, and a mirror that carried the other module's configuration key would be a copy
     * that lies.
     */
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
         * It resolves inside this network. Allowed only when
         * {@code tcms.tracker.imports.allow-private-hosts} is on — this module's own switch, and
         * not {@code integration}'s, because the two families connect out for different reasons
         * and an operator who allows a self-hosted Jira Data Center to be imported from has not
         * thereby said anything about notification webhooks.
         */
        PRIVATE_DESTINATION
    }

    private final ImportProperties properties;

    /**
     * Public so the guard can be constructed directly in a test, which is the only way its matrix
     * can be driven against both settings of {@code allow-private-hosts} in one run.
     */
    public ImportUrlGuard(ImportProperties properties) {
        this.properties = properties;
    }

    /*
     * EVERYTHING BELOW IS A VERBATIM COPY of the region between the same markers in
     * integration/OutboundUrlGuard.java. Edit one, edit both —
     * ImportUrlGuardMirrorsTheOutboundGuardTest compares the two as text and both files are
     * declared in backend/build.gradle.kts's inputs.files so that a comment-only edit to either
     * one actually re-runs it.
     */
    /**
     * @param url what somebody typed, or what a caller is about to dial
     * @return {@link Verdict#ALLOWED}, or the first rule that refused — never a boolean, because
     *         both callers put the reason on a screen and "false" is not a sentence
     */
    // ---- BEGIN MIRRORED FROM integration/OutboundUrlGuard.java ----
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
    // ---- END MIRRORED ----
}
