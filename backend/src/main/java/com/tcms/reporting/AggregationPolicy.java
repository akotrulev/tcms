package com.tcms.reporting;

import java.util.Locale;
import java.util.Optional;

/**
 * How a case executed many times resolves to one result — the wire enum {@code AggregationPolicy}
 * in {@code docs/specs/openapi.yaml} (plan §6e), <strong>duplicated into this module on purpose</strong>.
 *
 * <h2>The fourth forced wire-shape copy, and the count is kept deliberately</h2>
 *
 * <p>{@code com.tcms.execution.AggregationPolicy} is the original and this is a second declaration
 * of the same five values. ERD §0.2 lets {@code reporting} depend on {@code tenancy}, {@code project}
 * and {@code identity} and on nothing else, so importing {@code execution}'s copy is a dependency
 * {@code ModuleStructureTest} refuses. ~~This codebase already carries three such families —
 * {@code ApiProblem} ×2, {@code UserRefView} ×3, and the storage ports — and slice-plan decision E
 * declines to create a {@code shared} module for a fourth: <em>"a fourth foundation module is a
 * decision, not an import"</em>, and one enum is the wrong reason to spend it.~~
 *
 * <p><strong>→ Superseded 2026-08-09 as to the families, not as to this enum.</strong> The other two
 * were extracted — the storage ports into {@code storage} (§125), {@code ApiProblem} into
 * {@code com.tcms.api} as {@code Problem} (§146), and {@code UserRefView} — which was <em>six</em>
 * copies rather than the three counted here — into {@code com.tcms.api.UserRef} (§147). <strong>This
 * copy stays</strong>, and the reason is the extraction's own charter: a type belongs in
 * {@code api} only if the <em>contract</em> defines it and more than one module serves it. The
 * contract does define {@code AggregationPolicy}, but this module and {@code execution} do not
 * <em>serve</em> one shape — they each own a vocabulary that must stay equal, which is what
 * {@code AggregateExecutionPolicyTest} pins. Moving it would make one module's enum the other's,
 * and the cross-check below would then compare a thing to itself.
 *
 * <p><strong>What is duplicated is the vocabulary. What is NOT duplicated is the algorithm</strong>,
 * and that asymmetry is the load-bearing half of decision D. {@code PlanStatisticsService.resolve}
 * is ~80 lines carrying three fixes §46 paid for in defects; a second Java copy of it would be E1 at
 * its most expensive. {@link AggregateExecutionReport} instead resolves the policy <em>in SQL over
 * {@code execution_fact}</em> — a different implementation, over different data, answering the same
 * question — and {@code AggregateExecutionCrossCheckTest} asserts the two agree against
 * <em>each other</em> rather than against literals.
 *
 * <p><strong>Four values and no more.</strong> A value added to one copy and not the other is a
 * report that silently refuses a policy the plan page offers, so {@code AggregateExecutionPolicyTest}
 * pins the two name sets equal — the check that makes a duplicated enum survivable, since nothing
 * else in the build can see that these two files are supposed to be the same list. It earned its
 * keep on 2026-08-07: {@code BEST} was removed (plan §8 #47) and that check is what makes removing
 * it from <em>one</em> file impossible to land, which matters more for a deletion than for an
 * addition — a missing value fails closed and loudly here, but a copy that still <em>accepts</em>
 * a retired policy would answer a number the plan page can no longer produce.
 */
enum AggregationPolicy {

    /** Each case counts once, using its most recent result. The contract's default. */
    LATEST,

    /** Each case counts once <em>per configuration</em>, so a case run on three browsers gives three. */
    LATEST_PER_CONFIG,

    /** A case that failed anywhere counts as failed. The release-gate reading. */
    WORST,

    /** Every execution counted separately. The only policy that is not a statement about cases. */
    ALL;

    /** The contract's {@code default: LATEST}, applied when the caller omits the parameter. */
    static final AggregationPolicy DEFAULT = LATEST;

    /**
     * Parses a wire value, case-insensitively.
     *
     * <p>Empty for anything unrecognised rather than throwing here: the caller turns it into a
     * refusal that names what <em>is</em> accepted, which is the difference between a client fixing
     * a typo in one attempt and guessing.
     */
    static Optional<AggregationPolicy> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        for (AggregationPolicy policy : values()) {
            if (policy.name().equalsIgnoreCase(value.trim())) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }

    /** Every accepted value, for the message a refusal prints. */
    static String accepted() {
        StringBuilder out = new StringBuilder();
        for (AggregationPolicy policy : values()) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(policy.name());
        }
        return out.toString();
    }

    /** The value as it is printed in the report header — the enum name, not a prose label. */
    String wireValue() {
        return name().toUpperCase(Locale.ROOT);
    }
}
