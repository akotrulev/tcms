package com.tcms.execution;

import java.util.List;

/**
 * One CI dialect, read into {@link IngestedResult}s — the Strategy behind {@link ReportParsers}.
 *
 * <p>A Strategy rather than a switch because the four bodies share nothing: two walk a DOM, two walk
 * a JSON tree, and the two in each pair disagree about where the outcome lives. What makes it an
 * <em>earned</em> one rather than ceremony is that the variants are added by migration — see
 * {@link ReportFormat} — so the interface is asked to serve a set that a database constraint bounds,
 * not an open-ended plugin surface.
 *
 * <h2>Every implementation is pure, and that is the testable half of the slice</h2>
 *
 * <p>No repository, no clock, no context. A parser turns a string into a list, so its whole
 * behaviour is assertable without a database — which matters here because the interesting cases are
 * the malformed and half-populated reports that a fixture cannot conveniently produce end to end.
 *
 * <h2>Refusing versus dropping</h2>
 *
 * <p>A parser <strong>throws</strong> {@link ReportXml.MalformedReportException} when the document
 * as a whole cannot be read, and <strong>drops</strong> an individual entry that carries no usable
 * name. The distinction is what the caller can do about it: an unreadable report is a mistake in
 * what they sent, and a nameless entry inside a readable one is a runner quirk they cannot fix and
 * would be told about on every single build.
 */
interface ReportParser {

    /** Which dialect this reads — the key it is registered under. */
    ReportFormat format();

    /**
     * Every test the report names, in document order.
     *
     * @throws ReportXml.MalformedReportException when the payload is not this dialect at all
     */
    List<IngestedResult> parse(String report);
}
