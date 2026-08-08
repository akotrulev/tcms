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
 * One cap on {@code Idempotency-Key}, declared in seven places — checked rather than shared.
 *
 * <h2>Why there is no shared constant</h2>
 *
 * <p>Seven services accept the header and each caps its length itself:
 * {@code CaseCreateService}, {@code SuiteWriteService} ({@code repository}),
 * {@code RunCreateService}, {@code PlanCreateService}, {@code IngestionEdit} ({@code execution}),
 * {@code DocPageWriteService} ({@code documentation}) and {@code BoardService} ({@code work}).
 * They live in four Modulith modules, and <strong>the only module all four may import is
 * {@code tenancy}</strong> — which everything depends on and which depends on nothing.
 *
 * <p>An HTTP header's length cap is not a tenancy concept, and §125 set the bar for what may enter
 * a foundation module when it extracted {@code storage}: a module every other module may depend on
 * must not be a grab bag, which is the reason §3's proposed {@code shared} was refused rather than
 * created. Moving four bytes of policy there to save six declarations would spend that decision on
 * the cheapest possible thing.
 *
 * <p>So the copies stay and the invariant becomes a computed check — the working agreement's rule
 * that <strong>a catalogue-wide invariant needs a computed check, not a careful reading</strong>,
 * whose most expensive instance is §108. What drift would cost here is a contract that is honest
 * per-endpoint and incoherent as a whole: one endpoint storing a key another refuses, so a client
 * generating keys of one shape works against half the API. Nothing fails; the writes just stop
 * being deduplicated on the endpoints that refuse, which is the failure the header exists to
 * prevent.
 *
 * <p>This test boots no Spring context and needs no database — the rule is a property of the source
 * text, exactly as {@link ConfigConventionTest}'s are. A check that is cheap is a check that
 * survives.
 */
@DisplayName("Idempotency-Key — one cap, seven declarations, no drift")
class IdempotencyKeyCapTest {

    /** The cap every declaration must agree on. Changing it means changing all seven. */
    private static final int EXPECTED_CAP = 200;

    /**
     * How many services declare it. A new create endpoint that accepts the header raises this, and
     * having to edit the number is the point: it is the moment to ask whether the eighth copy is
     * still the right trade against the shared symbol this test exists instead of.
     */
    private static final int EXPECTED_DECLARATIONS = 7;

    private static final Pattern DECLARATION = Pattern.compile(
            "\\bstatic final int MAX_IDEMPOTENCY_KEY\\s*=\\s*(\\d+)\\s*;");

    @Test
    @DisplayName("every MAX_IDEMPOTENCY_KEY in main declares the same cap")
    void everyCapAgrees() {
        Map<String, Integer> declared = declarations();

        assertThat(declared)
                .as("a service capping Idempotency-Key differently from its siblings makes one "
                        + "key format work on part of the API and fail on the rest")
                .isNotEmpty()
                .allSatisfy((file, cap) -> assertThat(cap)
                        .as("%s caps the key at %d", file, cap)
                        .isEqualTo(EXPECTED_CAP));
    }

    @Test
    @DisplayName("the declarations are exactly the seven services known to accept the header")
    void theCountIsPinned() {
        assertThat(declarations())
                .as("a create endpoint that accepts Idempotency-Key without capping it indexes "
                        + "whatever the caller sends; one that dropped its cap silently stopped "
                        + "refusing a hostile header")
                .hasSize(EXPECTED_DECLARATIONS);
    }

    @Test
    @DisplayName("the cap the contract publishes is the cap the code enforces")
    void theContractAgreesWithTheCode() {
        // openapi.yaml declares `schema: { type: string, maxLength: 200 }` on the shared
        // IdempotencyKey parameter. A generated client trims to the contract's number, so a
        // contract that promised more than the server stores would produce a 400 the client's own
        // types said was impossible.
        String contract = read(Path.of("../docs/specs/openapi.yaml"));
        Matcher parameter = Pattern.compile(
                "IdempotencyKey:.*?maxLength:\\s*(\\d+)", Pattern.DOTALL).matcher(contract);

        assertThat(parameter.find()).as("openapi.yaml declares an IdempotencyKey parameter")
                .isTrue();
        assertThat(Integer.parseInt(parameter.group(1))).isEqualTo(EXPECTED_CAP);
    }

    /** File name to the cap it declares, over every source file under {@code src/main}. */
    private static Map<String, Integer> declarations() {
        Map<String, Integer> found = new LinkedHashMap<>();
        for (Path file : sourceFiles()) {
            Matcher matcher = DECLARATION.matcher(read(file));
            while (matcher.find()) {
                found.put(file.getFileName().toString(), Integer.parseInt(matcher.group(1)));
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
