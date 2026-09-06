package com.tcms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <strong>There is no velocity in this product, and this is the check rather than the promise</strong>
 * — scope D6, and M9's fourth definition-of-done.
 *
 * <p>D6 removed the sprint. There is no {@code sprint} table, no {@code sprint_issue}, no
 * sprint-planning screen and no cadence a velocity would be measured over, so a chart or an endpoint
 * implying otherwise would be a lie about how the team plans — and the specific failure is that it
 * would look entirely plausible, because the parts to assemble one out of (cycle time per type, a
 * weekly flow) all now exist.
 *
 * <h2>Why this is a test and not a paragraph</h2>
 *
 * <p>"No velocity chart exists anywhere" is a property of the whole tree, and no screen can assert it
 * about itself. It is also the kind of claim that is true on the day it is written and quietly stops
 * being true — which is the general shape §152–§156 replaced four times, prose standing in for a
 * seam.
 *
 * <h2>What this half covers, and where the other half lives</h2>
 *
 * <p>{@code backend/src/main} and {@code docs/specs/openapi.yaml} — <strong>both already declared
 * test inputs</strong> of this task. The frontend and the shared packages are deliberately NOT read
 * here: {@code build.gradle.kts}' own paragraph argues against declaring {@code frontend/src} as a
 * backend input, because it is another stack's whole source tree and would re-run every backend test
 * on every unrelated component edit. So the other half of this rule is
 * {@code frontend/src/app/noVelocity.test.ts}, which runs in the job that already owns both those
 * trees. Two files, one rule, and each one declared where its corpus is cheap to watch.
 *
 * <h2>Prose is not a violation</h2>
 *
 * <p>The word has to be sayable, or the sentences that explain the absence become the thing that
 * fails — the trap this package has now met three times. Java comments and OpenAPI
 * {@code description:} blocks are stripped before the match, so a paragraph saying the product has
 * no velocity is invisible here and an identifier, field or path named after one is not.
 */
@DisplayName("no velocity chart, endpoint, verb or field exists in the backend or the contract (D6)")
class NoVelocityAnywhereTest {

    private static final Path BACKEND_SOURCE = Path.of("src", "main", "java");
    private static final Path CONTRACT = Path.of("..", "docs", "specs", "openapi.yaml");

    private static final Pattern VELOCITY = Pattern.compile("velocity", Pattern.CASE_INSENSITIVE);

    private static final Pattern JAVA_COMMENT =
            Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    @Test
    @DisplayName("the corpus is not empty — this rule cannot pass by finding nothing to read")
    void theCorpusIsNotEmpty() {
        assertThat(javaSources())
                .as("no source under " + BACKEND_SOURCE + " — the layout has moved and the rule "
                        + "below would pass over nothing")
                .hasSizeGreaterThan(200);
        assertThat(read(CONTRACT))
                .as("the contract is this rule's other corpus")
                .contains("openapi:");
    }

    @Test
    @DisplayName("nothing in the backend or the contract is named after a velocity")
    void noVelocity() {
        List<String> hits = new ArrayList<>();
        for (Path source : javaSources()) {
            if (VELOCITY.matcher(withoutJavaComments(read(source))).find()) {
                hits.add(source.toString());
            }
        }
        if (VELOCITY.matcher(withoutDescriptions(read(CONTRACT))).find()) {
            hits.add(CONTRACT.toString());
        }

        assertThat(hits)
                .as("""
                        Scope D6 removed the sprint velocity would be measured over, so there is \
                        nothing to compute it across and a figure implying otherwise reports a \
                        number nobody planned against. The parts to assemble one now exist — cycle \
                        time per type, a weekly flow — which is exactly why this is a check and not \
                        a comment. If a cadence is ever introduced, delete this rule in the commit \
                        that introduces it rather than carving an exception into it.""")
                .isEmpty();
    }

    /**
     * An OpenAPI document with its {@code description:} blocks removed.
     *
     * <p>YAML block scalars are indentation-delimited, so the strip runs line by line: once a
     * {@code description: |} or {@code >} opens, every following line indented further than the key
     * belongs to it. That is enough for this corpus — every prose mention in the contract is inside
     * one — and it is deliberately not a YAML parser, because a rule that needs a parser to read a
     * file the build already validates is a second reader that can disagree with the first.
     */
    private static String withoutDescriptions(String yaml) {
        StringBuilder kept = new StringBuilder();
        int blockIndent = -1;
        for (String line : yaml.split("\n", -1)) {
            String trimmed = line.strip();
            int indent = line.length() - line.stripLeading().length();

            if (blockIndent >= 0) {
                if (trimmed.isEmpty() || indent > blockIndent) {
                    continue;
                }
                blockIndent = -1;
            }
            if (trimmed.startsWith("description:") || trimmed.startsWith("summary:")
                    || trimmed.startsWith("- name:") || trimmed.startsWith("#")) {
                // A one-line `description: text` is dropped outright; a block scalar opens the skip
                // above. `- name:` carries the tag descriptions' parent, whose block is indented
                // under it.
                if (trimmed.endsWith("|") || trimmed.endsWith(">") || trimmed.startsWith("- name:")) {
                    blockIndent = indent;
                }
                continue;
            }
            kept.append(line).append('\n');
        }
        return kept.toString();
    }

    private static List<Path> javaSources() {
        try (Stream<Path> walk = Files.walk(BACKEND_SOURCE)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(BACKEND_SOURCE + " is this rule's corpus", unreadable);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("unreadable corpus " + path, unreadable);
        }
    }

    private static String withoutJavaComments(String source) {
        return JAVA_COMMENT.matcher(source).replaceAll(" ");
    }
}
