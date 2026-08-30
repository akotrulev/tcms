package com.tcms.documentation;

/**
 * What end of a {@link DocLink} the assertion comes from — {@code doc_link.source_type}, and
 * {@code BlockLink.sourceType} in {@code docs/specs/openapi.yaml}.
 *
 * <h2>Narrower than the column, on purpose</h2>
 *
 * <p>{@code V1}'s {@code CHECK} also admits {@code DEFECT} and {@code DOC_PAGE}, and {@code V2}
 * added {@code ISSUE}. None of the three is offered here and none is on the contract's enum,
 * because none has a writer yet: a defect is an external record this product links out to rather
 * than stores, a page-to-page link is a different feature that would need its own target semantics,
 * and the issue-to-specification edge arrives with module M1's tracker.
 *
 * <p>The column stays wider than this enum deliberately, rather than being narrowed by a migration
 * to match. Narrowing it would have to be undone by the slice that builds any of them, and a
 * {@code CHECK} that has been tightened and loosened again is a constraint nobody trusts. What the
 * application refuses to write is expressed here, in the type; what the schema permits is a
 * statement about the design, and the design has not changed.
 *
 * <p>Reading is the asymmetric case and it is safe: a row with either value simply fails to map, and
 * nothing can write one.
 */
enum LinkSourceType {

    /** {@code repository.TestCase}. The ordinary link — a case verifying a statement. */
    TEST_CASE,

    /**
     * {@code repository.Suite}. A whole folder of cases against one statement.
     *
     * <p>Offered because a plan's scenario often maps to a suite rather than to any single case, and
     * requiring the author to link twenty cases one at a time is how a traceability feature stops
     * being used in its second week.
     */
    SUITE
}
