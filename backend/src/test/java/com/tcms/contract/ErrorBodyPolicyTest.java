package com.tcms.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which refusals carry a document and which are a status and nothing else — checked against the
 * server rather than against the contract's own prose.
 *
 * <h2>What this exists for</h2>
 *
 * <p>Until §148 {@code openapi.yaml} declared a {@code Problem} body on <strong>every</strong> 401
 * and on 111 of its 113 declared 404s, and the server writes one on neither. Every 404 in
 * {@code src/main} is {@code ResponseEntity.notFound().build()}; every 401 is written by the
 * security chain — an {@code HttpStatusEntryPoint} or {@code PermissionVersionFilter} — which sets
 * a status and no content. So both were promises no code kept, in the direction that generates
 * broken clients: §145's defect class again, on a rule the server is right about.
 *
 * <p><strong>The consumers had already noticed and compensated silently.</strong>
 * {@code @tcms/client}'s {@code problemFrom} falls back to {@code statusText} with a comment
 * reading <em>"the API's own 401 is bodiless"</em>, and {@code openapi.yaml} itself said so in a
 * path-level comment on the invitation family (<em>"EVERY failure is 404 with no body"</em>) while
 * the shared response two thousand lines below declared the opposite. Two spellings of one status
 * in one document, and nothing compared them — the {@code MAX_IDEMPOTENCY_KEY} shape from §136 and
 * the {@code UserRefView} tally from §146, a third time.
 *
 * <h2>Why a source scan, when this repo prefers the live mapping</h2>
 *
 * <p>{@code ContractParameterBindingTest} argues at length for reading the running handler mapping
 * rather than the source, and that argument is right for the question it asks — a binding is a
 * property of the mapping. <strong>Body-presence is not.</strong> Nothing in the mapping, the
 * return type or any reflective view says whether a given {@code switch} arm calls {@code .build()}
 * or {@code .body(…)}; the only way to see it without driving all 127 operations through every
 * refusal each can produce is to read the text that decides it. That is §108's lesson in its
 * general form: when the defect lives in the text, the check has to read the text.
 *
 * <p>The 401 half needs no scan and does not get one — {@code EndpointAccessTest} already sweeps
 * every mapped endpoint anonymously, so it asserts the empty body there, live, on all of them.
 */
@DisplayName("Error bodies — what the contract promises against what the server writes")
class ErrorBodyPolicyTest {

    /** Statuses this API answers with a status line and nothing else. */
    private static final Set<String> BODILESS_STATUSES = Set.of("401", "404");

    /**
     * The 403s that write no body, and the reason each one does not.
     *
     * <p><strong>Unlike the 401 and the 404, a 403 normally carries a {@code Problem}</strong> —
     * it has something actionable to say (which permission, at what scope) and both non-SPA
     * clients print its {@code detail}. These three are the exceptions, each argued in a comment
     * at the site, and the list is here so that a fourth is a decision rather than a habit.
     */
    private static final Set<String> BODILESS_FORBIDDEN_SITES = Set.of(
            // The response is typed to a binary Resource. A caller that asked for bytes and is
            // handed a JSON document under a status it can already read learns nothing it could
            // act on.
            "reporting/ReportController.java",
            // Registering an artifact, and unregistering one. Neither refusal has a field to land
            // a sentence on and neither is something the caller can change about the request —
            // unlike the ForbiddenAtScope sibling two arms below, which names the scope so the
            // form can put the message on the scope selector.
            "documentation/ArtifactController.java",
            "documentation/ArtifactFileController.java");

    /**
     * The one file allowed to name a 404 status directly: it serves {@code /error}, where the
     * container has resolved a path with no operation at all and the {@code Problem} body is the
     * point (§146, {@code PreHandlerRefusal}). That 404 is not an operation's answer and this
     * rule is not about it.
     */
    private static final String CONTAINER_ERROR_PATH = "api/ProblemErrorAttributes.java";

    private static OpenApiContract contract;

    @BeforeAll
    static void parse() {
        contract = OpenApiContract.load();
    }

    // ------------------------------------------------------------- contract ----

    @Test
    @DisplayName("no declared 401 or 404 promises a body")
    void bodilessStatusesDeclareNoContent() {
        List<String> offenders = new ArrayList<>();
        int checked = 0;

        for (OpenApiContract.Operation operation : contract.operations()) {
            Map<String, Object> responses = responsesOf(operation);
            for (Map.Entry<String, Object> entry : responses.entrySet()) {
                if (!BODILESS_STATUSES.contains(entry.getKey())) {
                    continue;
                }
                checked++;
                Map<String, Object> response = contract.resolve(asMap(entry.getValue()));
                if (response.containsKey("content")) {
                    offenders.add(operation.describe() + "  " + entry.getKey());
                }
            }
        }

        assertThat(checked)
                .as("no operation declares a 401 or a 404 at all — the response walk has stopped "
                        + "matching the contract's shape, and this asserted nothing")
                .isGreaterThan(100);

        assertThat(offenders)
                .as("""
                        These operations declare a body on a status this server answers empty.

                        Every 404 in src/main is `ResponseEntity.notFound().build()` and every 401 is
                        written by the security chain with no content. A declared body on either is a
                        promise nothing keeps: both copies of schema.d.ts generate the payload type, a
                        client author writes the parse, and it reads an empty string at runtime.

                        The emptiness is deliberate on both. A 404 must not say WHICH of the ids
                        failed to resolve (§6t) and a 401 must not say which of the four credential
                        conditions did — that is the disclosure each status exists to prevent, and it
                        is the only thing a body here could add.

                        Declaring one anyway:""")
                .isEmpty();
    }

    // --------------------------------------------------------------- source ----

    @Test
    @DisplayName("no handler writes a body on a 404")
    void noHandlerWritesABodyOnA404() {
        List<String> offenders = new ArrayList<>();
        int sites = 0;

        for (Path file : mainSources()) {
            String source = read(file);
            for (int at : occurrencesOf(source, "notFound()")) {
                sites++;
                if (!continuesWith(source, at + "notFound()".length(), ".build()")) {
                    offenders.add(describe(file, source, at));
                }
            }
            // The scan above knows one spelling, so the other one has to be banned outright or
            // it is the hole the check leaves: `status(HttpStatus.NOT_FOUND).body(…)` writes the
            // same status, carries a document, and contains no `notFound()` to be found. The
            // codebase writes `notFound()` at all 94 sites, so an occurrence here is either a
            // body arriving by the back door or a second spelling of a rule that has one.
            if (!CONTAINER_ERROR_PATH.equals(moduleRelative(file))) {
                for (int at : occurrencesOf(source, "HttpStatus.NOT_FOUND")) {
                    offenders.add(describe(file, source, at) + "  (writes 404 the long way)");
                }
            }
        }

        assertThat(sites)
                .as("the scan found almost no 404 sites — `notFound()` has probably been replaced "
                        + "by another spelling, and this test is now checking nothing")
                .isGreaterThan(80);

        assertThat(offenders)
                .as("""
                        These sites answer 404 with something other than an empty body.

                        `NotFound` in openapi.yaml declares no content, so a body here makes the
                        contract false for every generated client at once. It is also the disclosure
                        §6t forbids: the only thing such a body could usefully carry is which of the
                        path's ids failed to resolve, and that turns the endpoint into an oracle for
                        rows the caller may not see.

                        Field-level detail belongs on the 400, where the value was legal and merely
                        unacceptable, and where the caller is allowed to know what was wrong with it.

                        Sites writing a 404 body:""")
                .isEmpty();
    }

    @Test
    @DisplayName("the 403s that write no body are the three that argued for it")
    void bodilessForbiddenSitesArePinned() {
        Set<String> found = new LinkedHashSet<>();
        List<String> located = new ArrayList<>();
        int sites = 0;

        for (Path file : mainSources()) {
            String source = read(file);
            for (int at : occurrencesOf(source, "status(HttpStatus.FORBIDDEN)")) {
                sites++;
                if (continuesWith(source, at + "status(HttpStatus.FORBIDDEN)".length(), ".build()")) {
                    found.add(moduleRelative(file));
                    located.add(describe(file, source, at));
                }
            }
        }

        assertThat(sites)
                .as("the scan found almost no 403 sites — the spelling has changed and this test "
                        + "is now checking nothing")
                .isGreaterThan(40);

        assertThat(found)
                .as("""
                        The set of 403s that answer with no body has changed.  Sites found: %s

                        `Forbidden` in openapi.yaml says the body is present everywhere except three
                        named sites, so this set is part of the contract rather than an implementation
                        detail. A bodiless 403 is defensible — the report download is typed to a
                        binary Resource, and two artifact refusals have no field to land a sentence on
                        — but it is the exception, and §141's finding is why: a refusal that does not
                        say what to do next is the one a model retries.

                        A new entry means either the site should carry a `Problem`, or the contract's
                        `Forbidden` description and this list both need editing. Do not add a row to
                        make a red run green.""".formatted(located))
                .isEqualTo(BODILESS_FORBIDDEN_SITES);
    }

    // -------------------------------------------------------------- helpers ----

    private static Map<String, Object> responsesOf(OpenApiContract.Operation operation) {
        return asMap(operation.body().get("responses"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /**
     * Every Java file under the backend's main source set.
     *
     * <p>Located by walking up from the working directory for the same reason
     * {@link OpenApiContract#load()} does: Gradle runs with it set to {@code backend/} and an IDE
     * does not, and a relative literal fails as a {@code NoSuchFileException} that reads like the
     * source tree is missing.
     */
    private static List<Path> mainSources() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("src/main/java/com/tcms"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException(
                    "backend/src/main/java not found above " + Path.of("").toAbsolutePath());
        }
        try (Stream<Path> tree = Files.walk(dir.resolve("src/main/java/com/tcms"))) {
            return tree.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Integer> occurrencesOf(String source, String needle) {
        List<Integer> at = new ArrayList<>();
        for (int i = source.indexOf(needle); i >= 0; i = source.indexOf(needle, i + 1)) {
            at.add(i);
        }
        return at;
    }

    /**
     * Whether the next non-whitespace text after {@code from} is {@code continuation}.
     *
     * <p>Whitespace-tolerant on purpose: this codebase writes both
     * {@code ResponseEntity.status(FORBIDDEN).build()} and the same call broken across two lines
     * by the formatter, and a check that only matched the first spelling would report the second
     * as carrying a body.
     */
    private static boolean continuesWith(String source, int from, String continuation) {
        int i = from;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
            i++;
        }
        return source.startsWith(continuation, i);
    }

    /** {@code execution/RunController.java:412}, which is what a failure message has to give. */
    private static String describe(Path file, String source, int at) {
        int line = 1;
        for (int i = 0; i < at; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return moduleRelative(file) + ":" + line;
    }

    /** The module and file — {@code reporting/ReportController.java}. */
    private static String moduleRelative(Path file) {
        int count = file.getNameCount();
        return file.subpath(count - 2, count).toString();
    }
}
