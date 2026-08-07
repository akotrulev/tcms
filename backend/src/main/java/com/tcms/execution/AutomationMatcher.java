package com.tcms.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * §6c's four-step matching ladder — joining an incoming CI result to a case.
 *
 * <pre>
 * 1. Exact automation_key match                       CaseFacts.caseIdsByAutomationKey
 * 2. A display key embedded in the test name          CaseFacts.caseIdsByDisplayKey
 * 3. Normalized full name → learned mapping           AutomationKeyMappingRepository
 * 4. No match                                          counted and named, nothing written
 * </pre>
 *
 * <h2>Three PASSES over a shrinking set, not a chain of responsibility per test</h2>
 *
 * <p>The ladder reads like a per-test chain and must not be written as one. A CI report is
 * thousands of tests; a chain asks each step per test, so a 5,000-test report is 15,000 queries
 * inside one transaction — which is not a slow endpoint, it is an endpoint that times out on the
 * first real customer and works perfectly on every fixture. So each step is one batched
 * {@code IN (…)} over everything still unmatched, and the ladder's <em>order</em> is preserved by
 * running the passes in order rather than by asking in order.
 *
 * <p>The cost of that shape is that a step's query runs even when the previous one matched
 * everything — which is why each lookup returns early on an empty set. The benefit is that the
 * query count is three, whatever the report's size.
 *
 * <h2>Step 2's pattern is fixed, and that is a stated departure from §6c</h2>
 *
 * <p>§6c specifies "explicit case-ID annotation embedded in the test name ({@code @TC-1042},
 * configurable regex per project)". There is no column for a per-project regex, and inventing one is
 * a schema decision with no consumer asking for it yet. {@link #DISPLAY_KEY} is the shape every
 * display key in the product has (§6t: an uppercase prefix, a hyphen, a number), and every token it
 * finds is looked up against <em>this project's</em> {@code test_case.key}.
 *
 * <p>That bound is what makes the fixed pattern safe. A false extraction — a test named
 * {@code HTTP-404 returns not found} yields {@code HTTP-404} — costs one entry in an {@code IN}
 * list and matches nothing, because {@code HTTP} is not a key prefix of this project. A wrong
 * <em>configurable</em> regex, by contrast, could match a real key of a real case and silently post
 * a result to it. The failure modes are not symmetric, and the fixed one fails safe.
 *
 * <p><strong>A name yielding two distinct keys matches NEITHER.</strong> {@code TC-1 and TC-2 both
 * work} is a test that names two cases, and picking the first is picking one arbitrarily — a result
 * landing on a case the author did not single out. Left unmatched, so a person decides.
 *
 * <h2>Why nothing is auto-created for an unmatched test</h2>
 *
 * <p>§6c offers "quarantine folder, optionally auto-creating a case stub"; the option is declined
 * (slice plan decision J). A quarantine folder is not expressible since §98 flattened suites, and
 * more decisively a CI job that can mint cases turns every test <em>rename</em> into a permanent
 * duplicate of a case that already exists — which is the identity problem {@code automationKey}
 * exists to solve, reintroduced by the feature meant to use it. The unmatched names are returned so
 * a person can bind them through {@code POST /automation-mappings}, which is step 3's writer.
 */
@Component
class AutomationMatcher {

    /**
     * §6t's display-key shape — an uppercase prefix, a hyphen, a number.
     *
     * <p>Word-bounded at both ends so {@code TC-1042} is found inside {@code shouldRefund[TC-1042]}
     * and {@code X-TC-1042} does not yield {@code TC-1042}. The prefix is capped at ten characters
     * because a display key's prefix is a project's short code and an unbounded run of capitals
     * would match constants, HTTP verbs and screaming-snake identifiers, filling the lookup with
     * tokens that can never be keys.
     */
    private static final Pattern DISPLAY_KEY = Pattern.compile("\\b([A-Z][A-Z0-9]{0,9}-\\d+)\\b");

    private final CaseFacts cases;
    private final AutomationKeyMappingRepository mappings;

    AutomationMatcher(CaseFacts cases, AutomationKeyMappingRepository mappings) {
        this.cases = cases;
        this.mappings = mappings;
    }

    /** Which rung a result came in on — carried onto the job row so a reader can see it. */
    enum MatchKind {

        /** Step 1 — the case's own {@code automation_key}. */
        AUTOMATION_KEY,

        /** Step 2 — a display key embedded in the test name. */
        DISPLAY_KEY,

        /** Step 3 — a mapping a person confirmed. */
        LEARNED_MAPPING,

        /** Step 4 — nothing. */
        UNMATCHED
    }

    /**
     * One parsed result and where it landed.
     *
     * @param caseId null exactly when {@code kind} is {@link MatchKind#UNMATCHED}
     */
    record Match(IngestedResult result, MatchKind kind, UUID caseId) {

        boolean matched() {
            return caseId != null;
        }
    }

    /**
     * Runs the ladder over a whole report.
     *
     * <p>Order is preserved: the returned list is parallel to {@code results}, so the job row's
     * record of what happened lines up with the report a caller can still read.
     *
     * <p><strong>Two results naming the same case are both returned as matches.</strong> That is a
     * real case — a parameterised test posting three rows against one case — and resolving it is
     * the writer's job, not the matcher's: {@link IngestionService} records the most severe outcome
     * per case, which is the same rule {@link CucumberReportParser} applies to a scenario's steps.
     * Collapsing here would throw away which tests contributed, which is precisely what the job row
     * exists to record.
     */
    List<Match> match(UUID projectId, List<IngestedResult> results) {
        List<Match> matches = new ArrayList<>(results.size());
        for (IngestedResult result : results) {
            matches.add(new Match(result, MatchKind.UNMATCHED, null));
        }

        // Step 1 — exact automation_key, on the full name AS SENT.
        Map<String, UUID> byAutomationKey =
                cases.caseIdsByAutomationKey(projectId, distinctNames(matches, r -> r.fullName()));
        resolve(matches, MatchKind.AUTOMATION_KEY,
                result -> byAutomationKey.get(result.fullName()));

        // Step 2 — a display key inside the name. The extraction happens twice (once to build the
        // lookup, once to resolve) rather than being cached: it is a regex over a few thousand
        // short strings, and a cache keyed on the name is a second place the two could disagree
        // about what a name yields.
        Set<String> tokens = new LinkedHashSet<>();
        for (Match match : matches) {
            if (!match.matched()) {
                tokens.addAll(displayKeysIn(match.result().fullName()));
            }
        }
        Map<String, UUID> byDisplayKey = cases.caseIdsByDisplayKey(projectId, tokens);
        resolve(matches, MatchKind.DISPLAY_KEY, result -> soleKey(result, byDisplayKey));

        // Step 3 — what a person confirmed.
        Set<String> normalized = distinctNames(matches, IngestedResult::normalizedName);
        Map<String, UUID> byMapping = new LinkedHashMap<>();
        if (!normalized.isEmpty()) {
            for (AutomationKeyMapping mapping : mappings.findByNormalizedTestNameIn(normalized)) {
                byMapping.put(mapping.getNormalizedTestName(), mapping.getCaseId());
            }
        }
        resolve(matches, MatchKind.LEARNED_MAPPING,
                result -> byMapping.get(result.normalizedName()));

        return List.copyOf(matches);
    }

    /** Every distinct name among the results that are still unmatched. */
    private static Set<String> distinctNames(
            List<Match> matches, java.util.function.Function<IngestedResult, String> naming) {

        Set<String> names = new LinkedHashSet<>();
        for (Match match : matches) {
            if (!match.matched()) {
                String name = naming.apply(match.result());
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** Fills in every still-unmatched entry the lookup can answer, in place. */
    private static void resolve(
            List<Match> matches,
            MatchKind kind,
            java.util.function.Function<IngestedResult, UUID> lookup) {

        for (int i = 0; i < matches.size(); i++) {
            Match match = matches.get(i);
            if (match.matched()) {
                continue;
            }
            UUID caseId = lookup.apply(match.result());
            if (caseId != null) {
                matches.set(i, new Match(match.result(), kind, caseId));
            }
        }
    }

    /**
     * The one case this name's embedded keys resolve to, or null when it names none or several.
     *
     * <p>Several <em>distinct</em> cases is the ambiguous case the class header describes. Several
     * tokens resolving to the same case is not ambiguous and matches.
     */
    private static UUID soleKey(IngestedResult result, Map<String, UUID> byDisplayKey) {
        Set<UUID> found = new LinkedHashSet<>();
        for (String token : displayKeysIn(result.fullName())) {
            UUID caseId = byDisplayKey.get(token);
            if (caseId != null) {
                found.add(caseId);
            }
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }

    /** Every display-key-shaped token in {@code name}, in order, deduplicated. */
    private static Set<String> displayKeysIn(String name) {
        Set<String> tokens = new LinkedHashSet<>();
        if (name == null) {
            return tokens;
        }
        Matcher matcher = DISPLAY_KEY.matcher(name);
        while (matcher.find()) {
            tokens.add(matcher.group(1));
        }
        return tokens;
    }
}
