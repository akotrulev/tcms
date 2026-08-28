package com.tcms.integration;

/**
 * The masked form of a credential — the only part of one that ever leaves this module.
 *
 * <p>A person configuring a project has to be able to tell <em>which</em> webhook is on a channel
 * without being able to use it. Showing nothing at all makes "is this the right one" unanswerable
 * and invites the fix that follows from that: an administrator replaces a working secret to be
 * sure, and every replacement is a chance to paste the wrong one.
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
 * <p><strong>End-anchored, and four characters.</strong> The tail of a Slack webhook is its
 * per-integration part, so it discriminates; the host and path prefixes are identical across every
 * webhook of a kind and would discriminate nothing while looking informative. Four is enough to
 * tell two apart and far short of the ~24 characters of entropy the tail carries.
 */
public final class SecretHint {

    /** How many trailing characters survive. */
    private static final int KEPT = 4;

    private static final String MASK = "…";

    private SecretHint() {
    }

    /**
     * @param secret the plaintext credential, or null for a kind that has none
     * @return the hint to store, or null — never the empty string, so "no secret" has one spelling
     */
    public static String of(String secret) {
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
