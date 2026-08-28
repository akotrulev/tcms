package com.tcms.integration;

import java.util.regex.Pattern;

/**
 * Takes a credential back out of a failure message before anything durable sees it.
 *
 * <h2>The leak this exists to stop is invisible in every green run</h2>
 *
 * <p>An HTTP client's exception message routinely quotes the URL it was posting to — that is the
 * single most useful thing it could say, and for this one caller the URL <em>is</em> the
 * credential. So the natural, correct-looking code path writes a live webhook secret into
 * {@code notification_delivery.error}: a table an operator browses, a backup copies, and a support
 * bundle exports. Nothing fails, no test goes red, and the credential is at rest in plaintext in
 * the one column somebody is guaranteed to read when they are already confused.
 *
 * <p>Two passes, because either alone leaves a hole:
 *
 * <ul>
 *   <li><strong>The known secret</strong> — the exact string this delivery was made with, replaced
 *       wherever it appears. Exact and complete, and it cannot cover a message that quotes only
 *       part of the URL.</li>
 *   <li><strong>Anything URL-shaped</strong> — every {@code http(s)://…} in the text loses its
 *       path and query, keeping scheme and host. A partially-quoted secret, a redirect target, and
 *       a second webhook mentioned by a proxy are all covered by that, and what survives is the
 *       part an operator actually needs: which host refused.</li>
 * </ul>
 *
 *
 * <h2>Public since 004, and only just</h2>
 *
 * <p>It was package-private, which was the right visibility while the only caller sat beside it.
 * The tracker sub-package holds one credential per <em>person</em> per host and needs exactly this
 * class — {@code SecretCipher}'s argument about a recoverable value is the same argument — and a
 * sub-package is a different package to the compiler. The alternatives were a second cipher (two
 * places a key is used, which is how a rotation misses one) or flattening thirty tracker classes
 * into this package.
 *
 * <p><strong>Widening this does not widen the module.</strong>
 * {@code ArchitectureRulesTest.crossModuleDependenciesPointOnlyAtTheFoundation} forbids any module
 * outside {@code integration} from importing anything here, {@code public} or not, so the reachable
 * set is unchanged and the modifier says "the tracker package may call this" rather than "anyone
 * may".
 * <p>The second pass alone would be enough for a URL credential and is kept as the general rule;
 * the first exists because a future channel kind's secret need not be a URL at all, and the day
 * one is a bare token the general pass sees nothing.
 */
public final class SecretRedaction {

    /** Scheme and host are kept; everything from the path onward is what carries the secret. */
    private static final Pattern URL = Pattern.compile("(https?://[^/\\s]+)[^\\s\"']*");

    public static final String REPLACEMENT = "[redacted]";

    private SecretRedaction() {
    }

    /**
     * @param text   the failure as the transport described it, possibly null
     * @param secret the credential this delivery used, or null for a channel that has none
     */
    public static String apply(String text, String secret) {
        if (text == null) {
            return null;
        }
        String redacted = text;
        if (secret != null && !secret.isBlank()) {
            redacted = redacted.replace(secret.trim(), REPLACEMENT);
        }
        return URL.matcher(redacted).replaceAll("$1/" + REPLACEMENT);
    }
}
