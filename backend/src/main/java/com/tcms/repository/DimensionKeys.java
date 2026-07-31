package com.tcms.repository;

/**
 * The classification dimension keys {@code V2} seeds, in the one place both readers of them can
 * see.
 *
 * <p>Matched by <strong>key</strong> rather than by position or display name: the name is
 * per-company and a client may rename theirs — the two locked dimensions are locked against
 * <em>deletion</em>, not against renaming — while the key is what {@code openapi.yaml}'s
 * {@code testTypeId} / {@code testLevelId} / {@code featureId} parameters are named after.
 *
 * <p>Extracted from {@code CaseService} when the case detail became the second reader. Two copies
 * of the string {@code "test_type"} is the kind of duplication that stays correct until someone
 * fixes a typo in one of them, and the symptom would be a filter and a rendered field disagreeing
 * about which dimension they mean.
 */
final class DimensionKeys {

    /** The one MULTI-cardinality dimension (plan §6d) — a case may carry several. */
    static final String FEATURE = "feature";

    /** SINGLE — enforced by a {@code V2} trigger at write time. */
    static final String TEST_TYPE = "test_type";

    /** SINGLE — enforced by a {@code V2} trigger at write time. */
    static final String TEST_LEVEL = "test_level";

    private DimensionKeys() {
    }
}
