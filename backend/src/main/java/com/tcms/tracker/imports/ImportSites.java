package com.tcms.tracker.imports;

/**
 * The site check every credential-carrying operation makes before it dials anything.
 *
 * <h2>Why it is here and not only inside {@link ImportHttp}</h2>
 *
 * <p>{@link ImportHttp} guards every URL it composes, and that is not this check repeated. The
 * guard belongs to the <strong>connection</strong> here — a policy about the address somebody typed
 * into a form, decided before any adapter sees the credential — and to the whole composed address
 * there, which is the only place a site that passes and a path that goes elsewhere can be told
 * apart. Only this one is reachable by an {@link ImportSource} that composes no URL, which is every
 * fake today and would be any future source speaking something other than HTTP; without it, such a
 * source would slip the check entirely and the endpoints' declared {@code 400} would be
 * unreachable.
 *
 * <h2>Why a static helper and not a method on either service</h2>
 *
 * <p>Both {@link ImportPreviewService} and {@link ImportRunService} must make it, and neither may
 * be the other's dependency: the preview would then be reachable from the writer, or the writer
 * from a read. A method on {@link ImportUrlGuard} was the other candidate and is worse — that class
 * reproduces {@code integration.OutboundUrlGuard} between two marker comments and is pinned to it
 * as text by {@code ImportUrlGuardMirrorsTheOutboundGuardTest}, so every line added to it is a line
 * somebody has to reason about against the original.
 */
final class ImportSites {

    private ImportSites() {
    }

    /**
     * @throws ImportUrlRefusedException which both controllers' handler answers {@code 400} — the
     *         address is wrong and the person who typed it can fix it, which is a different answer
     *         from {@link ImportSourceUnavailableException}'s 502
     */
    static void vet(ImportUrlGuard guard, ImportConnection connection) {
        ImportUrlGuard.Verdict verdict = guard.check(connection.siteUrl());
        if (verdict != ImportUrlGuard.Verdict.ALLOWED) {
            throw ImportUrlRefusedException.forUrl(connection.siteUrl(), verdict);
        }
    }
}
