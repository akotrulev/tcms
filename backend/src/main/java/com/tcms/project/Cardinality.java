package com.tcms.project;

/**
 * How many values of one dimension a case may carry (plan §6d).
 *
 * <p>Not a cosmetic hint. It is enforced in the database by the SINGLE-cardinality trigger in
 * {@code V2}, because the constraint spans two tables — {@code case_classification} holds the
 * value, and only {@code classification_dimension} knows the cardinality — which a {@code CHECK}
 * predicate cannot reach.
 */
public enum Cardinality {

    /** At most one value per case. Test Type and Test Level. */
    SINGLE,

    /** Any number of values per case. Execution Phase and Feature. */
    MULTI
}
