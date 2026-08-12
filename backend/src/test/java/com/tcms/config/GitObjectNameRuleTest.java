package com.tcms.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One answer to "what is a commit id", declared in two places — checked rather than shared.
 *
 * <h2>Why there is no shared constant</h2>
 *
 * <p>{@code documentation.ExternalLinkService} has validated a git object name since §169, when
 * {@code doc_link}'s two external target kinds got a writer. §177 made it a second question:
 * {@code execution.RunEdit} validates {@code test_run.commit_sha} for Git traceability, and the two
 * modules may not import each other (plan §3 — {@code execution} and {@code documentation} cross via
 * events, and there is nothing to publish here).
 *
 * <p>The module that <em>could</em> hold it refuses it in as many words. {@code api}'s charter is
 * <em>"a type belongs here only if the contract defines it and more than one module serves it"</em>,
 * with the exclusion spelled out: <em>"a helper two modules happen to share does not qualify — that
 * is a copy, or an event."</em> A regex is not a wire shape; putting it there would widen the one
 * module in the application that declares no dependencies at all into the grab bag §125 refused when
 * it declined to create {@code shared}.
 *
 * <p>So the copies stay and the invariant becomes a computed check — the working agreement's rule
 * that <strong>a catalogue-wide invariant needs a computed check, not a careful reading</strong>, and
 * {@link IdempotencyKeyCapTest}'s shape exactly, for the same reason at the same altitude. What drift
 * would cost here is a value the product disagrees with itself about: an abbreviated sha a doc link
 * accepts and a run refuses, in a product whose two git-bearing surfaces are read side by side on the
 * case detail — the Traceability panel's link and the last run that tested it.
 *
 * <p><strong>This test does not assert the pattern is the RIGHT one.</strong> Two properties of it
 * are deliberate and both are recorded in the declarations' own javadoc — the bound is loose at the
 * bottom because teams paste abbreviated shas out of terminals, and 64-character SHA-256 object names
 * are refused as a known limit rather than an oversight. Widening either is one number in two files,
 * and this test is what makes it two rather than one-and-a-drift.
 */
@DisplayName("A git object name — one rule, two declarations, no drift")
class GitObjectNameRuleTest {

    /**
     * The rule both declarations must spell identically: 7 to 40 hex characters.
     *
     * <p>Compared as <em>source text</em> and not as a compiled {@code Pattern}, which is the only
     * comparison available across two package-private constants in two modules — and is the stronger
     * one anyway: {@code [0-9a-fA-F]{7,40}} and {@code (?i)[0-9a-f]{7,40}} accept the same strings
     * and are not the same rule to the next reader who edits one of them.
     */
    private static final String EXPECTED_PATTERN = "[0-9a-fA-F]{7,40}";

    /**
     * How many places declare it.
     *
     * <p>Two: {@code ExternalLinkService} (§169) and {@code RunEdit} (§177). Having to edit this
     * number is the point — it is the moment to ask whether a third copy is still the right trade
     * against the shared symbol this test exists instead of. At three, {@code api}'s charter still
     * refuses it and the honest alternatives are a port or a domain event, neither of which is worth
     * a regex; at that point the answer is probably still "copy, and raise the number".
     */
    private static final int EXPECTED_DECLARATIONS = 2;

    private static final Pattern DECLARATION = Pattern.compile(
            "\\bPattern SHA\\s*=\\s*Pattern\\.compile\\(\"(.*?)\"\\)\\s*;");

    @Test
    @DisplayName("every git-object-name pattern in main spells the same rule")
    void everyDeclarationAgrees() {
        Map<String, String> declared = declarations();

        assertThat(declared)
                .as("an abbreviated sha one surface accepts and another refuses is one product "
                        + "holding two answers to what a commit id is")
                .isNotEmpty()
                .allSatisfy((file, pattern) -> assertThat(pattern)
                        .as("%s declares %s", file, pattern)
                        .isEqualTo(EXPECTED_PATTERN));
    }

    @Test
    @DisplayName("the declarations are exactly the two known validators")
    void theCountIsPinned() {
        assertThat(declarations())
                .as("a third surface validating a commit id is a decision, not an import — see the "
                        + "class header")
                .hasSize(EXPECTED_DECLARATIONS)
                .containsOnlyKeys("ExternalLinkService.java", "RunEdit.java");
    }

    @Test
    @DisplayName("the rule accepts an abbreviated sha and a full one, and refuses a branch name")
    void theRuleItself() {
        Pattern sha = Pattern.compile(EXPECTED_PATTERN);

        assertThat(sha.matcher("4f9c2b1").matches()).as("seven characters, pasted from a terminal")
                .isTrue();
        assertThat(sha.matcher("4f9c2b1e8a7d6c5b4a39281706f5e4d3c2b1a098").matches())
                .as("a full SHA-1 object name").isTrue();

        assertThat(sha.matcher("4f9c2b").matches()).as("six is below the bound").isFalse();
        assertThat(sha.matcher("release/4.3").matches())
                .as("THE failure both declarations exist for — a ref name stored as a commit id")
                .isFalse();
        assertThat(sha.matcher("4f9c2b1e8a7d6c5b4a39281706f5e4d3c2b1a0984f9c2b1e8a7d6c5b4a392817")
                        .matches())
                .as("64 characters: a SHA-256 object name, refused as a recorded limit")
                .isFalse();
    }

    /** File name to the pattern it declares, over every source file under {@code src/main}. */
    private static Map<String, String> declarations() {
        Map<String, String> found = new LinkedHashMap<>();
        for (Path file : sourceFiles()) {
            Matcher matcher = DECLARATION.matcher(read(file));
            while (matcher.find()) {
                found.put(file.getFileName().toString(), matcher.group(1));
            }
        }
        return found;
    }

    private static List<Path> sourceFiles() {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<Path> java = new ArrayList<>();
            files.filter(path -> path.getFileName().toString().endsWith(".java")).forEach(java::add);
            return java;
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
