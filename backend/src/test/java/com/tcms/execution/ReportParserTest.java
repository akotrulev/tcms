package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The four report parsers — pure logic, so no Spring context and no Postgres.
 *
 * <p>Every parser turns a string into a list, which is the reason this class can be exhaustive where
 * an end-to-end fixture cannot: the interesting inputs are the malformed and half-populated reports
 * a real runner emits on a bad day, and constructing those through an HTTP request and a database
 * would cost more per case than the case is worth.
 *
 * <p><strong>The assertions this class exists for are the all-green ones.</strong> Each parser has
 * one shape that, read carelessly, reports a failing suite as passing — a JUnit {@code <error>}
 * nested one level too deep, a Cucumber scenario whose last step is skipped because an earlier one
 * failed, a status string nobody recognised. Those are the tests named "…is not a pass", and they
 * are the ones to keep if this class is ever trimmed.
 */
@DisplayName("The CI report parsers — and the four ways a red suite could read as green")
class ReportParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JUnitReportParser junit = new JUnitReportParser();
    private final TestNgReportParser testng = new TestNgReportParser();
    private final AllureReportParser allure = new AllureReportParser(JSON);
    private final CucumberReportParser cucumber = new CucumberReportParser(JSON);

    @Nested
    @DisplayName("JUnit / Surefire XML")
    class JUnit {

        @Test
        @DisplayName("a passing case says nothing, so PASSED is read from the absence of children")
        void passingCaseHasNoMarker() {
            List<IngestedResult> results = junit.parse("""
                    <testsuite name="s">
                      <testcase classname="com.x.OrderTest" name="cancels" time="0.42"/>
                    </testsuite>
                    """);

            assertThat(results).singleElement().satisfies(result -> {
                assertThat(result.outcome()).isEqualTo(IngestedOutcome.PASSED);
                assertThat(result.fullName()).isEqualTo("com.x.OrderTest#cancels");
                assertThat(result.durationMs()).isEqualTo(420);
            });
        }

        @Test
        @DisplayName("failure, error and skipped are three outcomes and failure wins over error")
        void theThreeMarkers() {
            List<IngestedResult> results = junit.parse("""
                    <testsuites>
                      <testsuite name="s">
                        <testcase classname="c" name="failed"><failure message="expected 3"/></testcase>
                        <testcase classname="c" name="errored"><error message="boom"/></testcase>
                        <testcase classname="c" name="skipped"><skipped/></testcase>
                        <testcase classname="c" name="both">
                          <failure message="assertion"/><error message="teardown"/>
                        </testcase>
                      </testsuite>
                    </testsuites>
                    """);

            assertThat(results).extracting(IngestedResult::outcome).containsExactly(
                    IngestedOutcome.FAILED,
                    IngestedOutcome.ERRORED,
                    IngestedOutcome.SKIPPED,
                    // A case carrying both: the more severe one, never whichever came first in the
                    // document, or the same test reads differently under two runners.
                    IngestedOutcome.FAILED);
            assertThat(results.getFirst().message()).isEqualTo("expected 3");
        }

        @Test
        @DisplayName("a SUITE-level error is not attributed to the first case in it — not a pass")
        void suiteLevelErrorDoesNotLeakIntoACase() {
            // getElementsByTagName searches DESCENDANTS, so an implementation reading the suite's
            // children through it marks the first testcase as errored — and, worse, the same
            // mistake inverted makes a nested suite's failures invisible. Both cases are pinned:
            // the case is passed (its own children are empty) and it is the only result.
            List<IngestedResult> results = junit.parse("""
                    <testsuite name="outer">
                      <error message="the suite could not start"/>
                      <testcase classname="c" name="a"/>
                    </testsuite>
                    """);

            assertThat(results).singleElement()
                    .extracting(IngestedResult::outcome).isEqualTo(IngestedOutcome.PASSED);
        }

        @Test
        @DisplayName("a TestNG document posted as JUNIT is refused, not read as zero tests")
        void wrongDialectIsRefused() {
            assertThatThrownBy(() -> junit.parse(
                    "<testng-results><suite><test/></suite></testng-results>"))
                    .isInstanceOf(ReportXml.MalformedReportException.class);
        }
    }

    @Nested
    @DisplayName("TestNG XML")
    class TestNg {

        @Test
        @DisplayName("the class comes from the ANCESTOR, so interleaved classes are not mixed up")
        void classNameComesFromTheEnclosingElement() {
            List<IngestedResult> results = testng.parse("""
                    <testng-results><suite><test>
                      <class name="com.x.A">
                        <test-method status="PASS" name="one" duration-ms="12" is-config="false"/>
                      </class>
                      <class name="com.x.B">
                        <test-method status="FAIL" name="two" duration-ms="34" is-config="false">
                          <exception class="java.lang.AssertionError">
                            <message>expected 3</message>
                          </exception>
                        </test-method>
                      </class>
                    </test></suite></testng-results>
                    """);

            assertThat(results).extracting(IngestedResult::fullName)
                    .containsExactly("com.x.A#one", "com.x.B#two");
            assertThat(results.get(1).message()).isEqualTo("expected 3");
            // duration-ms is already millis. JUnit's `time` is decimal SECONDS, and the two look
            // interchangeable while differing by a factor of a thousand.
            assertThat(results.get(1).durationMs()).isEqualTo(34);
        }

        @Test
        @DisplayName("configuration methods are dropped — a @BeforeSuite is not a test")
        void configurationMethodsAreDropped() {
            List<IngestedResult> results = testng.parse("""
                    <testng-results><suite><test><class name="c">
                      <test-method status="FAIL" name="setUp" is-config="true"/>
                      <test-method status="PASS" name="realTest" is-config="false"/>
                    </class></test></suite></testng-results>
                    """);

            assertThat(results).extracting(IngestedResult::name).containsExactly("realTest");
        }

        @Test
        @DisplayName("an unrecognised status is not a pass")
        void unknownStatusIsNotAPass() {
            List<IngestedResult> results = testng.parse("""
                    <testng-results><suite><test><class name="c">
                      <test-method name="a" status="WHAT"/>
                      <test-method name="b"/>
                    </class></test></suite></testng-results>
                    """);

            assertThat(results).extracting(IngestedResult::outcome)
                    .containsExactly(IngestedOutcome.ERRORED, IngestedOutcome.ERRORED);
        }
    }

    @Nested
    @DisplayName("Allure JSON")
    class Allure {

        @Test
        @DisplayName("the class comes from the testClass LABEL, not from splitting fullName")
        void classNameComesFromTheLabel() {
            // Splitting Allure's dotted fullName on the last dot produces
            // "com.x.OrderTest.cancels[EUR," for a parameterised test. The label is exact.
            List<IngestedResult> results = allure.parse("""
                    [{"name":"cancels[EUR, 3]","fullName":"com.x.OrderTest.cancels[EUR, 3]",
                      "status":"failed","start":1690000000000,"stop":1690000000420,
                      "statusDetails":{"message":"expected 3"},
                      "labels":[{"name":"testClass","value":"com.x.OrderTest"}]}]
                    """);

            assertThat(results).singleElement().satisfies(result -> {
                assertThat(result.className()).isEqualTo("com.x.OrderTest");
                assertThat(result.fullName()).isEqualTo("com.x.OrderTest#cancels[EUR, 3]");
                assertThat(result.outcome()).isEqualTo(IngestedOutcome.FAILED);
                assertThat(result.durationMs()).isEqualTo(420);
            });
        }

        @Test
        @DisplayName("broken and unknown are not passes, and a bare object is accepted")
        void brokenAndUnknownAreNotPasses() {
            assertThat(allure.parse("{\"name\":\"a\",\"status\":\"broken\"}"))
                    .singleElement()
                    .extracting(IngestedResult::outcome).isEqualTo(IngestedOutcome.ERRORED);
            assertThat(allure.parse("[{\"name\":\"a\",\"status\":\"unknown\"}]"))
                    .singleElement()
                    .extracting(IngestedResult::outcome).isEqualTo(IngestedOutcome.ERRORED);
        }

        @Test
        @DisplayName("a stop with no start is no duration, not fifty-four years")
        void halfADurationIsNoDuration() {
            assertThat(allure.parse("[{\"name\":\"a\",\"status\":\"passed\",\"stop\":1690000000000}]"))
                    .singleElement()
                    .extracting(IngestedResult::durationMs).isNull();
        }
    }

    @Nested
    @DisplayName("Cucumber JSON")
    class Cucumber {

        @Test
        @DisplayName("a scenario failing MID-WAY is failed, though its last step is skipped")
        void theWorstStepWins() {
            // THE all-green case for this dialect. Cucumber marks every step after a failure as
            // skipped, so "take the last step's status" reports this scenario as SKIPPED — and a
            // variant that treats skipped-as-passed reports it green.
            List<IngestedResult> results = cucumber.parse("""
                    [{"name":"Orders","elements":[
                      {"type":"scenario","name":"Cancel an order","steps":[
                        {"keyword":"Given ","name":"an order","result":{"status":"passed","duration":1000000}},
                        {"keyword":"When ","name":"I cancel","result":{"status":"failed","duration":2000000,
                         "error_message":"expected 3"}},
                        {"keyword":"Then ","name":"it is cancelled","result":{"status":"skipped"}}]}]}]
                    """);

            assertThat(results).singleElement().satisfies(result -> {
                assertThat(result.outcome()).isEqualTo(IngestedOutcome.FAILED);
                assertThat(result.fullName()).isEqualTo("Orders#Cancel an order");
                // Nanoseconds here, millis in Allure, decimal seconds in JUnit. All called
                // "duration".
                assertThat(result.durationMs()).isEqualTo(3);
                assertThat(result.message()).isEqualTo("When I cancel: expected 3");
            });
        }

        @Test
        @DisplayName("undefined glue is not a skip, and a background is not a test")
        void undefinedIsNotASkipAndBackgroundsAreDropped() {
            List<IngestedResult> results = cucumber.parse("""
                    [{"name":"Orders","elements":[
                      {"type":"background","name":"setup","steps":[
                        {"result":{"status":"failed","error_message":"db down"}}]},
                      {"type":"scenario","name":"No glue","steps":[
                        {"result":{"status":"undefined"}}]},
                      {"type":"scenario","name":"Empty","steps":[]}]}]
                    """);

            assertThat(results).extracting(IngestedResult::name)
                    .containsExactly("No glue", "Empty");
            assertThat(results).extracting(IngestedResult::outcome)
                    // An empty scenario ran nothing — crediting it a pass is the same direction.
                    .containsExactly(IngestedOutcome.ERRORED, IngestedOutcome.SKIPPED);
        }
    }

    @Nested
    @DisplayName("The XML hardening — the one that is not about test results at all")
    class Hardening {

        @Test
        @DisplayName("a DOCTYPE with an external entity is refused, both dialects")
        void externalEntitiesAreRefused() {
            // Read by a default JAXP parser this inlines /etc/passwd into the test name, which is
            // then stored, matched and served back. The endpoint takes this document from a
            // credential holder over the network; the flags in ReportXml are the whole defence.
            String payload = """
                    <?xml version="1.0"?>
                    <!DOCTYPE t [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                    <testsuite name="s"><testcase classname="c" name="&x;"/></testsuite>
                    """;

            assertThatThrownBy(() -> junit.parse(payload))
                    .isInstanceOf(ReportXml.MalformedReportException.class);
            assertThatThrownBy(() -> testng.parse(payload))
                    .isInstanceOf(ReportXml.MalformedReportException.class);
        }

        @Test
        @DisplayName("a billion-laughs expansion is refused by the same declaration ban")
        void entityExpansionIsRefused() {
            assertThatThrownBy(() -> junit.parse("""
                    <?xml version="1.0"?>
                    <!DOCTYPE t [<!ENTITY a "xx"><!ENTITY b "&a;&a;&a;&a;&a;">]>
                    <testsuite name="s"><testcase classname="c" name="&b;"/></testsuite>
                    """))
                    .isInstanceOf(ReportXml.MalformedReportException.class);
        }
    }

    @Nested
    @DisplayName("Normalization — the key the learned mappings are stored under")
    class Normalization {

        @Test
        @DisplayName("case and whitespace are normalized away; punctuation is not")
        void whatNormalizationDoesAndDoesNot() {
            assertThat(IngestedResult.normalize("  com.X.OrderTest#Cancels   An  Order "))
                    .isEqualTo("com.x.ordertest#cancels an order");
            // Punctuation survives on purpose: stripping it merges "Order#cancel" and
            // "Order cancel", which binds a human's confirmation to the wrong one of two tests.
            assertThat(IngestedResult.normalize("Order#cancel"))
                    .isNotEqualTo(IngestedResult.normalize("Order cancel"));
        }

        @Test
        @DisplayName("a result with no class normalizes to its bare name")
        void noClassMeansNoSeparator() {
            IngestedResult result =
                    new IngestedResult(null, "Cancels An Order", IngestedOutcome.PASSED, null, null);

            assertThat(result.fullName()).isEqualTo("Cancels An Order");
            assertThat(result.normalizedName()).isEqualTo("cancels an order");
        }
    }
}
