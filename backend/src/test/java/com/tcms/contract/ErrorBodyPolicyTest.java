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
     * Statuses this API always answers with a {@code Problem}, which is the other half of the rule
     * and was unpinned until §149.
     *
     * <p>§148 pinned the two that carry nothing and recorded in as many words that these were not
     * audited site by site — <em>"a {@code .build()} on a 409 would pass today"</em>. The direction
     * matters as much here as there: a refusal with no body is one a caller cannot act on, and
     * §141's finding is what that costs — the caller is a model, and the failure it cannot read is
     * the one it retries.
     *
     * <p>All three are actionable by construction, which is why the rule is absolute rather than
     * carrying an exception list like {@code BODILESS_FORBIDDEN_SITES}. A 400 names the field, a
     * 409 names the blocker, a 429 names the number of seconds to wait.
     */
    private static final Set<String> BODIED_STATUSES = Set.of("400", "409", "422", "429");

    /**
     * The spellings that write a bodied status, and what each must be followed by.
     *
     * <p>Same reasoning as the {@code HttpStatus.NOT_FOUND} ban below: a scan that knows one
     * spelling leaves the others as its hole, so the ones it does not know are banned outright
     * (see {@link #noBackDoorSpellingOfABodiedStatus}).
     */
    private static final List<String> BODIED_SITE_SPELLINGS = List.of(
            "badRequest()",
            "unprocessableEntity()",
            "status(HttpStatus.BAD_REQUEST)",
            "status(HttpStatus.CONFLICT)",
            "status(HttpStatus.UNPROCESSABLE_ENTITY)",
            "status(HttpStatus.TOO_MANY_REQUESTS)");

    /**
     * The four sites that put {@code errors} on something other than a validation rejection, and
     * therefore key it by a blocker rather than by a field.
     *
     * <p>Pinned for the reason the bodiless 403s are: it is a documented property of the envelope
     * ({@code Problem.errors} in {@code openapi.yaml} names these four), so a fifth is a decision
     * about the contract rather than a local choice. <strong>What is not negotiable is the value</strong>
     * — see {@link #everyErrorsValueIsASentence}, which is the rule §149 was written for.
     */
    private static final Set<String> NON_VALIDATION_ERRORS_SITES = Set.of(
            // The two deletes that refuse because something still points at the row. The key names
            // the blocking population — `children`, `grants` — because there is no input field to
            // name: the request is a DELETE with no body at all.
            "identity/TeamController.java",
            "project/ProjectController.java",
            // `scope` on the artifact 403s: the form has a scope selector, so the message has
            // somewhere to land even though the refusal is about the caller rather than the value.
            "documentation/ArtifactController.java",
            "documentation/ArtifactFileController.java");

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

    @Test
    @DisplayName("every declared 400, 409, 422 and 429 promises a body")
    void bodiedStatusesDeclareContent() {
        List<String> offenders = new ArrayList<>();
        int checked = 0;

        for (OpenApiContract.Operation operation : contract.operations()) {
            for (Map.Entry<String, Object> entry : responsesOf(operation).entrySet()) {
                if (!BODIED_STATUSES.contains(entry.getKey())) {
                    continue;
                }
                checked++;
                if (!contract.resolve(asMap(entry.getValue())).containsKey("content")) {
                    offenders.add(operation.describe() + "  " + entry.getKey());
                }
            }
        }

        // `TooManyRequests` is referenced by no operation on purpose — it is written by a filter
        // before routing, so it is not a property of any one operation (§141) — which puts it
        // outside the walk above and would leave the one status a model is most likely to meet
        // unchecked.
        Map<String, Object> rateLimited = componentResponse("TooManyRequests");
        checked++;
        if (!rateLimited.containsKey("content")) {
            offenders.add("components/responses/TooManyRequests  429");
        }

        assertThat(checked)
                .as("the response walk found almost no 400/409/422 — it has stopped matching the "
                        + "contract's shape and this asserted nothing")
                .isGreaterThan(70);

        assertThat(offenders)
                .as("""
                        These refusals are declared with no body, and this API always writes one.

                        This is the other half of the 401/404 rule above, and it fails in the
                        opposite direction: there the contract promised a document nothing wrote,
                        here it withholds one every site sends. A generated client types the
                        response as `never`, so the caller that most needs the message — the CLI
                        prints `field: message`, the MCP server hands those lines to a model — is
                        the one whose types say there is nothing to read.

                        All three statuses are actionable by construction. A 400 names the field, a
                        409 names the blocker, a 429 names the seconds. §141: a refusal that does
                        not say what to do next is the one a caller retries unchanged.

                        Declaring one empty:""")
                .isEmpty();
    }

    // --------------------------------------------------------------- source ----

    @Test
    @DisplayName("no handler omits the body on a 400, 409 or 422")
    void noHandlerOmitsABodyOnABodiedStatus() {
        List<String> offenders = new ArrayList<>();
        int sites = 0;

        for (Path file : mainSources()) {
            String source = read(file);
            for (String spelling : BODIED_SITE_SPELLINGS) {
                for (int at : occurrencesOf(source, spelling)) {
                    sites++;
                    if (!continuesWith(source, at + spelling.length(), ".body(")) {
                        offenders.add(describe(file, source, at) + "  " + spelling);
                    }
                }
            }
        }

        assertThat(sites)
                .as("the scan found almost no 400/409 sites — a spelling has changed and this test "
                        + "is now checking nothing")
                .isGreaterThan(80);

        assertThat(offenders)
                .as("""
                        These sites answer a bodied status with no document.

                        `BadRequest` and `Conflict` in openapi.yaml both declare a `Problem`, so an
                        empty one makes the contract false for every generated client — the mirror
                        of what §148 fixed, arriving from the other side.

                        It is also the failure §141 spent a slice on: the 400 is the one refusal a
                        caller can fix from the response alone, and it can only do that if the
                        response says which field. `Problem.errors` is that, and `.build()` drops it.

                        Sites refusing without a body:""")
                .isEmpty();
    }

    @Test
    @DisplayName("no back-door spelling of a bodied status")
    void noBackDoorSpellingOfABodiedStatus() {
        // The scan above matches six spellings, so anything that writes one of these statuses
        // another way is invisible to it. Banning the alternatives outright is what makes that
        // scan a rule rather than a sample — §148's `HttpStatus.NOT_FOUND` ban, generalised.
        List<String> banned = List.of("status(400)", "status(409)", "status(422)", "status(429)",
                "HttpStatusCode.valueOf(", "SC_BAD_REQUEST", "SC_CONFLICT");
        List<String> offenders = new ArrayList<>();

        for (Path file : mainSources()) {
            String source = read(file);
            for (String spelling : banned) {
                for (int at : occurrencesOf(source, spelling)) {
                    offenders.add(describe(file, source, at) + "  " + spelling);
                }
            }
        }

        assertThat(offenders)
                .as("""
                        A refusal is written here in a spelling the body-presence scan cannot see.

                        Write it as `ResponseEntity.badRequest()` or
                        `ResponseEntity.status(HttpStatus.CONFLICT)`, both followed by `.body(…)`.
                        A numeric literal or a `HttpStatusCode.valueOf` is not wrong on its own —
                        it is invisible, which is worse, because the check above then reports a
                        clean run over a codebase it did not read.

                        Sites:""")
                .isEmpty();
    }

    @Test
    @DisplayName("every literal `errors` value is a sentence, not a datum")
    void everyErrorsValueIsASentence() {
        List<String> offenders = new ArrayList<>();
        int values = 0;

        for (Path file : mainSources()) {
            String source = read(file);
            for (int at : occurrencesOf(source, "setProperty(\"errors\"")) {
                String method = enclosingMethod(source, at);
                for (int listAt : occurrencesOf(method, "List.of(")) {
                    values++;
                    String argument = balanced(method, listAt + "List.of(".length() - 1);
                    if (!argument.contains("\"")) {
                        offenders.add(describe(file, source, at) + "  List.of(" + argument + ")");
                    }
                }
            }
        }

        assertThat(values)
                .as("no literal `errors` value was inspected — the construction has moved and this "
                        + "test is now checking nothing")
                .isGreaterThanOrEqualTo(4);

        assertThat(offenders)
                .as("""
                        An `errors` value here carries no words, so it is a datum rather than a
                        message.

                        `Problem.errors` in openapi.yaml says every value is a complete sentence a
                        client may print verbatim, and that is not decoration: `@tcms/cli` prints
                        `name: value` under the failure and `@tcms/mcp` hands the same lines to a
                        model. §149 found one site sending `children: 3` — the SPA rendered it,
                        because that one dialog knew to compose a sentence around it, and the two
                        callers that cannot see a screen were told a bare number.

                        Put the count in the sentence. `TeamController` is the shape.

                        Values with no words in them:""")
                .isEmpty();
    }

    @Test
    @DisplayName("the sites keying `errors` by a blocker rather than a field are the four named")
    void nonValidationErrorsSitesArePinned() {
        Set<String> found = new LinkedHashSet<>();

        for (Path file : mainSources()) {
            String source = read(file);
            for (int at : occurrencesOf(source, "setProperty(\"errors\"")) {
                String method = enclosingMethod(source, at);
                // A validation rejection takes its map from the service and names input fields;
                // these four build it here, from literal keys, on a refusal with no field to name.
                if (method.contains("List.of(") || method.contains("Map.of(")) {
                    found.add(moduleRelative(file));
                }
            }
        }

        assertThat(found)
                .as("""
                        The set of refusals keying `errors` by something that is not an input field
                        has changed.

                        `Problem.errors` in openapi.yaml names these four — `children` and `grants`
                        on the two deletes that refuse, `scope` on the artifact 403s — so the set is
                        part of the contract. A fifth means either the key should be a field name,
                        or the schema's description needs editing too. Do not add a row to make a
                        red run green.

                        Found:""")
                .isEqualTo(NON_VALIDATION_ERRORS_SITES);
    }

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

    /**
     * A named entry under {@code components/responses}, reached the only way this helper can — as
     * a {@code $ref}, which {@code resolve} throws on if it dangles. That throw is the check that
     * the component still exists, so a renamed one fails loudly instead of asserting over nothing.
     */
    private static Map<String, Object> componentResponse(String name) {
        return contract.resolve(Map.of("$ref", "#/components/responses/" + name));
    }

    /**
     * The source of the method containing {@code at}, so a rule about how a value is built can be
     * asked of the method that builds it rather than of the whole file.
     *
     * <p>Deliberately crude — it walks back to the nearest member declaration and forward to its
     * matching brace. It is reading a fixed handful of private factory methods in controllers, all
     * of which this project's formatter indents identically; a parser would be more machinery than
     * the question needs.
     */
    private static String enclosingMethod(String source, int at) {
        int start = -1;
        for (String modifier : List.of("\n    private", "\n    public", "\n    static")) {
            start = Math.max(start, source.lastIndexOf(modifier, at));
        }
        if (start < 0) {
            return source;
        }
        int open = source.indexOf('{', start);
        return open < 0 ? source.substring(start) : source.substring(start, balancedEnd(source, open));
    }

    /** The text between {@code open}'s bracket and its match, exclusive. */
    private static String balanced(String source, int open) {
        return source.substring(open + 1, balancedEnd(source, open) - 1);
    }

    /** One past the bracket matching the one at {@code open}. Handles {@code (} and <code>{</code>. */
    private static int balancedEnd(String source, int open) {
        char opener = source.charAt(open);
        char closer = opener == '(' ? ')' : '}';
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == opener) {
                depth++;
            } else if (c == closer) {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return source.length();
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
