package com.tcms.integration;

/**
 * The masked form of a credential — the only part of one that ever leaves this module.
 *
 * <p>A person configuring a project has to be able to tell <em>which</em> webhook is on a channel
 * without being able to use it. Showing nothing at all makes "is this the right one" unanswerable
 * and invites the fix that follows from that: an administrator replaces a working secret to be
 * sure, and every replacement is a chance to paste the wrong one.
 *
 * <p><strong>End-anchored, and four characters.</strong> The tail of a Slack webhook is its
 * per-integration part, so it discriminates; the host and path prefixes are identical across every
 * webhook of a kind and would discriminate nothing while looking informative. Four is enough to
 * tell two apart and far short of the ~24 characters of entropy the tail carries.
 */
final class SecretHint {

    /** How many trailing characters survive. */
    private static final int KEPT = 4;

    private static final String MASK = "…";

    private SecretHint() {
    }

    /**
     * @param secret the plaintext credential, or null for a kind that has none
     * @return the hint to store, or null — never the empty string, so "no secret" has one spelling
     */
    static String of(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        String trimmed = secret.trim();
        if (trimmed.length() <= KEPT) {
            // Too short to mask meaningfully, so nothing of it is shown. A secret this size is
            // almost certainly a mistake, and the screen saying "configured" is the honest answer.
            return MASK;
        }
        return MASK + trimmed.substring(trimmed.length() - KEPT);
    }
}
