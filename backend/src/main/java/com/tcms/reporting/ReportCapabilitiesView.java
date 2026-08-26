package com.tcms.reporting;

import java.util.List;

/**
 * What this deployment can actually produce — the contract's {@code ReportCapabilities}.
 *
 * <h2>Why a client has to be told, rather than knowing</h2>
 *
 * <p>{@link ReportFormat} has six values, the contract's {@code format} enum offers three, and every
 * shipped deployment renders two. The enum is deliberately <strong>not</strong> narrowed to what is
 * installed — {@code openapi.yaml} says so in as many words, because what a deployment can render is
 * a property of that deployment and a narrowed wire would make an installed PDF renderer
 * unreachable. The cost of that decision, until this record existed, was that every generated
 * client offered {@code PDF} and every deployment answered 400: the picker was wrong on every
 * install, and the only way to find out was to ask.
 *
 * <p>So the set moves from the wire to the <em>answer</em>. The refusal stays exactly where it was
 * ({@code ReportJobService.refusalFor}) — a client that asks for something this list does not
 * contain is still refused by name, because a capability read is a courtesy to a picker and never
 * the authority.
 *
 * <h2>{@code JSON} is renderable and is not offered</h2>
 *
 * <p>{@link JsonReportRenderer} is registered, so {@code ReportRenderers.supported()} contains
 * {@code JSON} — and it is the serialized model written for <em>every</em> job rather than a format
 * a person chooses ({@code V27}, and {@link ReportFormat#JSON}'s own comment). Offering it would put
 * a fourth option in a picker whose effect is to download what the screen already renders. The
 * filter is therefore against the contract's {@code format} enum, which is the set a person may
 * ask for, and not against the renderer registry alone.
 *
 * <h2>The type catalogue is here for the same reason and arrived later</h2>
 *
 * <p>{@code type} is an open string — a deployment's report set is whichever {@link
 * ReportComputation} beans it has — so until {@code types} was added the catalogue was discoverable
 * only by being <em>refused</em>: {@link ReportTypes#refusal} names what this server can produce,
 * and names it after the caller has already guessed wrong. Workable for the SPA, which has a
 * picker, and a dead end for an API caller with a {@code type} field and no list.
 *
 * @param formats every format this server can render <em>and</em> a caller may ask for, sorted, so
 *     a picker built from it is stable between reads.
 * @param types every report type this server can compute, sorted, straight off the registry rather
 *     than written down beside it.
 */
record ReportCapabilitiesView(List<String> formats, List<String> types) {

    /**
     * The contract's {@code format} enum, as the server's own copy.
     *
     * <p>It is a copy, and the alternative is worse: deriving "askable" from the renderers would
     * offer JSON, and deriving it from {@link ReportFormat} would offer CSV and XLSX — two formats
     * {@code V1} named and nothing has ever produced. {@code ReportCapabilitiesTest} is the
     * comparison that keeps it from drifting, both ways, against the contract's own enum.
     */
    private static final List<ReportFormat> ASKABLE =
            List.of(ReportFormat.PDF, ReportFormat.MD, ReportFormat.HTML);

    static ReportCapabilitiesView of(List<ReportFormat> supported, List<String> types) {
        return new ReportCapabilitiesView(
                ASKABLE.stream()
                        .filter(supported::contains)
                        .map(Enum::name)
                        .sorted()
                        .toList(),
                types);
    }
}
