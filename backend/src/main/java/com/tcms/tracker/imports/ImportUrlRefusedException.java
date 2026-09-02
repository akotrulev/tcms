package com.tcms.tracker.imports;

import java.net.URI;

/**
 * {@link ImportUrlGuard} refused the site before it was dialled — the contract's {@code 400} on
 * {@code POST /imports/source-projects} and {@code POST /imports/preview}.
 *
 * <p>A separate type from {@link ImportSourceUnavailableException} because the two are different
 * answers to different people. This one is <strong>400</strong>: the address is wrong and the
 * person who typed it can fix it. That one is <strong>502</strong>: the address was fine and the
 * far end did not co-operate. Folding them together would tell somebody with a typo that Jira is
 * down.
 *
 * <p>The message names the host and the verdict and never the path, so a refusal cannot echo
 * something somebody pasted.
 */
public class ImportUrlRefusedException extends RuntimeException {

    private final transient ImportUrlGuard.Verdict verdict;

    public ImportUrlRefusedException(String host, ImportUrlGuard.Verdict verdict) {
        super(sentence(host, verdict));
        this.verdict = verdict;
    }

    /**
     * The refusal for a URL, naming its host — <strong>the host, and never the path</strong>.
     *
     * <p>Both refusal sites build the sentence through here rather than each deriving the host,
     * because there is one rule about what a refusal may echo and two copies of it is one copy that
     * eventually echoes a path somebody pasted a token into.
     */
    public static ImportUrlRefusedException forUrl(String url, ImportUrlGuard.Verdict verdict) {
        return new ImportUrlRefusedException(hostOf(url), verdict);
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "that address" : host;
        } catch (IllegalArgumentException malformed) {
            return "that address";
        }
    }

    /** Which rule refused, so a screen can say more than "no" if it ever wants to. */
    public ImportUrlGuard.Verdict verdict() {
        return verdict;
    }

    /**
     * One sentence per verdict, because the four are four different mistakes and "this instance
     * will not connect to that address" is the same unhelpful answer to all of them.
     */
    private static String sentence(String host, ImportUrlGuard.Verdict verdict) {
        return switch (verdict) {
            case MALFORMED -> "That is not a site address this product can read. It should look "
                    + "like https://your-company.atlassian.net.";
            case NOT_HTTPS -> "The site address must start with https://. A token sent over plain "
                    + "http is already spent by the time anything redirects to TLS.";
            case UNRESOLVABLE -> "No server answers to " + host + ". Check the spelling of the "
                    + "site address.";
            case PRIVATE_DESTINATION -> "This instance will not connect to " + host + ", which "
                    + "resolves inside its own network. A self-hosted source on a private network "
                    + "needs an operator to set tcms.tracker.imports.allow-private-hosts.";
            case ALLOWED -> throw new IllegalArgumentException(
                    "ALLOWED is not a refusal; constructing this exception with it would report a "
                            + "destination that was never refused");
        };
    }
}
