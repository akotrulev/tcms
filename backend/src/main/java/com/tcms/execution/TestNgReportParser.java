package com.tcms.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * TestNG's {@code testng-results.xml}.
 *
 * <pre>{@code
 * <testng-results><suite><test><class name="com.x.OrderTest">
 *   <test-method status="FAIL" name="cancels" duration-ms="420" is-config="false">
 *     <exception class="java.lang.AssertionError"><message>expected 3</message></exception>
 *   </test-method>
 * </class></test></suite></testng-results>
 * }</pre>
 *
 * <h2>Three ways this differs from JUnit and each one is a defect if missed</h2>
 *
 * <p><strong>The outcome is stated, not inferred.</strong> {@code status} is
 * {@code PASS}/{@code FAIL}/{@code SKIP}, so there is no absence to read — but an unrecognised value
 * must not fall through to passed. It maps to {@link IngestedOutcome#ERRORED}, which is the same
 * conservative direction {@link JUnitReportParser} takes: an outcome nobody understood is a problem
 * to look at, never a green tick.
 *
 * <p><strong>The class name is on the ancestor.</strong> {@code <test-method>} carries only the
 * method name, so the class has to come from the enclosing {@code <class name="…">}. Read from the
 * parent chain rather than by pairing two flat lists, because a suite with several classes
 * interleaves them and index-pairing would attribute methods to the wrong class — a wrong
 * {@code fullName()}, so a wrong match, so results landing on somebody else's case.
 *
 * <p><strong>Configuration methods are in the same file.</strong> {@code is-config="true"} marks
 * {@code @BeforeMethod} and friends, which are not tests: a suite with a {@code @BeforeSuite} would
 * otherwise ingest one extra "test" per run, and a failing setup would be reported as a failing test
 * that does not exist in the repository. They are dropped.
 *
 * <p>{@code duration-ms} is already milliseconds here, where JUnit's {@code time} is decimal
 * seconds — so this parser does <em>not</em> call {@link ReportXml#secondsToMillis}. The two look
 * interchangeable and differ by a factor of a thousand.
 */
@Component
class TestNgReportParser implements ReportParser {

    @Override
    public ReportFormat format() {
        return ReportFormat.TESTNG;
    }

    @Override
    public List<IngestedResult> parse(String report) {
        Document document = ReportXml.parse(report);
        List<Element> methods = ReportXml.elements(document, "test-method");

        if (methods.isEmpty() && ReportXml.elements(document, "testng-results").isEmpty()) {
            throw new ReportXml.MalformedReportException(
                    "That does not look like a TestNG report — no <testng-results> "
                            + "or <test-method>.", null);
        }

        List<IngestedResult> results = new ArrayList<>();
        for (Element method : methods) {
            if ("true".equalsIgnoreCase(ReportXml.attribute(method, "is-config"))) {
                continue;
            }
            String name = ReportXml.attribute(method, "name");
            if (name == null) {
                continue;
            }
            results.add(new IngestedResult(
                    enclosingClassName(method),
                    name,
                    outcomeOf(ReportXml.attribute(method, "status")),
                    millis(ReportXml.attribute(method, "duration-ms")),
                    messageOf(method)));
        }
        return results;
    }

    /** The nearest {@code <class name="…">} ancestor, or null when the report has none. */
    private static String enclosingClassName(Element method) {
        for (var node = method.getParentNode(); node != null; node = node.getParentNode()) {
            if (node instanceof Element element && "class".equals(element.getNodeName())) {
                return ReportXml.attribute(element, "name");
            }
        }
        return null;
    }

    private static IngestedOutcome outcomeOf(String status) {
        if (status == null) {
            return IngestedOutcome.ERRORED;
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "PASS" -> IngestedOutcome.PASSED;
            case "FAIL" -> IngestedOutcome.FAILED;
            // SKIP is TestNG's own spelling and it also covers a method skipped because its
            // dependency failed, which is genuinely "not run" rather than "chose not to run".
            // Both are SKIPPED here: the distinction is a dependsOnMethods fact this report does
            // not carry, and guessing at it would be a claim the payload never made.
            case "SKIP" -> IngestedOutcome.SKIPPED;
            default -> IngestedOutcome.ERRORED;
        };
    }

    /**
     * {@code <exception>}'s {@code <message>}, then its own text, then nothing.
     *
     * <p>The nested {@code <message>} is where TestNG puts the assertion text and the exception's
     * body is the stack trace; taking the element's whole {@code textContent} first would produce a
     * comment that opens with several kilobytes of frames before saying what went wrong.
     */
    private static String messageOf(Element method) {
        List<Element> exceptions = ReportXml.children(method, "exception");
        if (exceptions.isEmpty()) {
            return null;
        }
        Element exception = exceptions.getFirst();
        List<Element> messages = ReportXml.children(exception, "message");
        if (!messages.isEmpty()) {
            String text = ReportXml.message(messages.getFirst());
            if (text != null) {
                return text;
            }
        }
        return ReportXml.message(exception);
    }

    private static Long millis(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
