package com.tcms.documentation;

import java.util.Locale;

/**
 * What a {@link DocLink} claims — {@code doc_link.relationship}.
 *
 * <p>All three of {@code V1}'s values, unlike {@link LinkSourceType}: these are not features waiting
 * to be built, they are three readings of one act, and the author is the only one who knows which
 * they mean. A link recorded as {@code VERIFIES} when the case merely {@code REFERENCES} the
 * statement is what turns a coverage number into a false claim.
 */
enum LinkRelationship {

    /** The case proves the statement. The default, and what a scenario link means. */
    VERIFIES,

    /** The case was written from the statement, without claiming to cover it. */
    DERIVED_FROM,

    /** The case mentions the statement — context, not coverage. */
    REFERENCES;

    /**
     * Parses the wire form, returning null on anything unknown.
     *
     * <p>Null rather than an exception for {@link DocBlockType#parse}'s reason: the caller is request
     * validation, and a field error naming the three values is more use than Jackson's own 400 about
     * an enum it could not bind.
     */
    static LinkRelationship parse(String wire) {
        if (wire == null) {
            return null;
        }
        for (LinkRelationship value : values()) {
            if (value.name().equals(wire.trim().toUpperCase(Locale.ROOT))) {
                return value;
            }
        }
        return null;
    }
}
