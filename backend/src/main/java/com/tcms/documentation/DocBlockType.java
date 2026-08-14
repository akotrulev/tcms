package com.tcms.documentation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * {@code DocBlock.blockType} in {@code docs/specs/openapi.yaml} — and the one enum in this codebase
 * whose wire values are <strong>lower case</strong>.
 *
 * <p>The contract has spelled them {@code heading · paragraph · list · code · table · image} since
 * the mock era and the SPA still branches on those strings — ~~and the MSW fixtures are written in
 * them~~, which stopped being a reason when the mock layer was deleted and was never the load-bearing
 * half: the SPA's branches are what a respelling would break. Every
 * other enum on the wire is upper case, so this is a genuine inconsistency in the contract — and
 * the wrong place to fix it is here. Changing the spelling would be a breaking contract change to
 * repair a cosmetic asymmetry, in the one slice that is trying to make the endpoint real.
 *
 * <p>So the wire form is authoritative and this maps to it in both directions. {@link
 * DocBlockTypeConverter} stores the <em>same</em> lower-case string in {@code doc_block.block_type},
 * rather than letting {@code @Enumerated(STRING)} write {@code HEADING} while the API serves
 * {@code heading}: two spellings of one value in two layers is a difference somebody eventually
 * writes a query against and gets zero rows from.
 */
public enum DocBlockType {
    HEADING,
    PARAGRAPH,
    LIST,
    CODE,
    TABLE,
    IMAGE;

    /** The contract's spelling — what goes on the wire and into the column. */
    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses the wire form.
     *
     * <p>Returns null rather than throwing on an unknown value, because the caller is request
     * validation: a {@code blockType} of {@code "hedaing"} must reach the client as a field error
     * naming the six valid values, not as Jackson's own 400 about a JSON parse — which names the
     * enum constants in a spelling the contract does not use.
     */
    @JsonCreator
    public static DocBlockType parse(String wire) {
        if (wire == null) {
            return null;
        }
        for (DocBlockType type : values()) {
            if (type.wire().equals(wire.trim().toLowerCase(Locale.ROOT))) {
                return type;
            }
        }
        return null;
    }
}
