package com.tcms.reporting;

/**
 * One format's view of a {@link ReportModel} — §6e's "one model, three renderers", with the third
 * (HTML) arriving in R4 and PDF refused for now (see {@link ReportRenderers}).
 *
 * <h2>Bytes, not a string</h2>
 *
 * <p>The return type is the artifact itself, encoding included, because that is what
 * {@link ReportStore#put} takes and what the download endpoint serves. A renderer that returned a
 * {@code String} would leave the charset decision to whoever wrote the bytes, and the two formats
 * here would then be UTF-8 by accident rather than by declaration — a report with a client's name in
 * it is the document where that shows up.
 *
 * <h2>{@link #contentType()} lives here and not on the download endpoint</h2>
 *
 * <p>The artifact row records a {@link ReportFormat} and nothing about how to serve it. Putting the
 * media type on the renderer keeps one fact in one place: R4 adding HTML is a class that answers
 * {@code text/html}, not a class plus a line in a {@code switch} somewhere in a controller that
 * nobody thinks to look at.
 */
interface ReportRenderer {

    /** The format this renderer produces. Exactly one renderer per format — see {@link ReportRenderers}. */
    ReportFormat format();

    /** The media type the download endpoint serves these bytes as. */
    String contentType();

    /** The extension a downloaded file gets, without the dot. */
    String fileExtension();

    /** The whole artifact. Called once per job per format; never streamed, never partial. */
    byte[] render(ReportModel model);
}
