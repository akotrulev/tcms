package com.tcms.execution;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Ant/Surefire JUnit XML — the dialect nearly every runner can emit, and the reason it is first.
 *
 * <pre>{@code
 * <testsuite name="…"><testcase classname="com.x.OrderTest" name="cancels" time="0.42">
 *   <failure message="expected 3">…stack…</failure>
 * </testcase></testsuite>
 * }</pre>
 *
 * <h2>The outcome is the absence of a child element, which is the trap</h2>
 *
 * <p>A passing {@code <testcase>} says nothing at all — there is no {@code status="passed"} — so the
 * outcome is read by looking for {@code <failure>}, {@code <error>} and {@code <skipped>} and
 * concluding {@link IngestedOutcome#PASSED} when none is present. Written the other way round (treat
 * an unrecognised shape as passed only when something says so) the whole file would ingest as
 * skipped; written carelessly, a document whose failures sit one level deeper than expected ingests
 * as <strong>all green</strong>, which is the direction this must never fail in.
 *
 * <p>Hence {@link ReportXml#children} and not {@code getElementsByTagName}: the latter searches
 * descendants, and nested {@code <testsuite>} elements are legal, so a suite-level {@code <error>}
 * would be attributed to the first test in it.
 *
 * <h2>{@code <testsuite>} is not required to be the root</h2>
 *
 * <p>Surefire aggregates into {@code <testsuites>}, single-module builds emit a bare
 * {@code <testsuite>}, and both are in the wild. Reading {@code <testcase>} elements from anywhere
 * in the document handles both without branching on the root's name — the case elements are what
 * this parser is about, and their depth is the build tool's business.
 */
@Component
class JUnitReportParser implements ReportParser {

    @Override
    public ReportFormat format() {
        return ReportFormat.JUNIT;
    }

    @Override
    public List<IngestedResult> parse(String report) {
        Document document = ReportXml.parse(report);
        List<Element> cases = ReportXml.elements(document, "testcase");

        if (cases.isEmpty() && ReportXml.elements(document, "testsuite").isEmpty()) {
            // A well-formed XML document with no JUnit elements at all is the wrong FORMAT, not an
            // empty report — most often TestNG posted under the wrong name. Reported as malformed
            // so the caller is told to check `format`, rather than answered with "0 tests ingested"
            // on a file that plainly has tests in it.
            throw new ReportXml.MalformedReportException(
                    "That does not look like a JUnit report — no <testsuite> or <testcase>.", null);
        }

        List<IngestedResult> results = new ArrayList<>();
        for (Element testCase : cases) {
            String name = ReportXml.attribute(testCase, "name");
            if (name == null) {
                continue;
            }
            results.add(new IngestedResult(
                    ReportXml.attribute(testCase, "classname"),
                    name,
                    outcomeOf(testCase),
                    ReportXml.secondsToMillis(ReportXml.attribute(testCase, "time")),
                    messageOf(testCase)));
        }
        return results;
    }

    /**
     * Failure beats error beats skipped beats passed.
     *
     * <p>An order is needed because a {@code <testcase>} may legally carry more than one child — a
     * test that failed its assertion and then errored in teardown is one row here — and the most
     * severe one is what a result must record. Silently taking whichever came first would make the
     * same test read differently depending on the runner's element order.
     */
    private static IngestedOutcome outcomeOf(Element testCase) {
        if (!ReportXml.children(testCase, "failure").isEmpty()) {
            return IngestedOutcome.FAILED;
        }
        if (!ReportXml.children(testCase, "error").isEmpty()) {
            return IngestedOutcome.ERRORED;
        }
        if (!ReportXml.children(testCase, "skipped").isEmpty()) {
            return IngestedOutcome.SKIPPED;
        }
        return IngestedOutcome.PASSED;
    }

    private static String messageOf(Element testCase) {
        for (String tag : List.of("failure", "error", "skipped")) {
            List<Element> found = ReportXml.children(testCase, tag);
            if (!found.isEmpty()) {
                return ReportXml.message(found.getFirst());
            }
        }
        return null;
    }
}
