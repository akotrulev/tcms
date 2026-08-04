package com.tcms.reporting;

/**
 * What a report's bytes are — {@code report_job.format} and {@code report_artifact.format}
 * ({@code V1}, widened by {@code V27}).
 *
 * <h2>Six values, and only two can be produced today</h2>
 *
 * <p>This enum is the set the <em>schema</em> may legitimately hold, not the set the server can
 * render, and keeping those two apart is deliberate. {@code V1} chose the five a person downloads
 * before any of them had a renderer; a job asking for one the product cannot produce is refused at
 * <strong>request</strong> time with a 400 naming the format, never accepted into a queue that fails
 * it an hour later — a client cannot tell an unsupported format from a broken server by watching a
 * job go {@code FAILED}.
 *
 * <p>Which two are renderable is {@code ReportRenderers}' answer and not a property of this enum, so
 * that adding the HTML renderer (R4) is one registration rather than an edit here plus an edit
 * there.
 */
public enum ReportFormat {

    /**
     * Refused at request time in v1 — no PDF renderer is installed and headless Chromium is not in
     * the image (slice plan §8). Kept in the enum because the contract's {@code format} offers it
     * and the refusal is a server behaviour with a message, not a narrowing of the wire.
     */
    PDF,

    /** The first format with a renderer: no template engine, no CSS inlining, no browser (§87). */
    MD,

    /**
     * §6e's primary format and the one a client is sent — one self-contained file, no CDN, no
     * script, charts drawn in CSS (§88 / R4). Also what the PDF slice will hand to a browser.
     */
    HTML,

    /** {@code V1}. No renderer, and no slice in the M2.5 plan builds one. */
    CSV,

    /** {@code V1}. No renderer, as above. */
    XLSX,

    /**
     * The serialized {@link ReportModel}, written for <strong>every</strong> job alongside whatever
     * was asked for ({@code V27}, slice-plan decision F). §6e: "the on-screen report view consumes
     * the same model via JSON, so what you see is what exports."
     *
     * <p>Not a format a client asks for — it is what makes the screen and the export provably one
     * model rendered twice, instead of a preview endpoint recomputing a second answer.
     */
    JSON
}
