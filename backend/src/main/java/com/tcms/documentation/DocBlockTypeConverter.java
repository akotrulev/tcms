package com.tcms.documentation;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores {@link DocBlockType} in {@code doc_block.block_type} using the contract's own lower-case
 * spelling, so the column and the API agree.
 *
 * <p>{@code @Enumerated(EnumType.STRING)} would write {@code HEADING} where the response says
 * {@code heading}. Both are self-consistent; together they are two vocabularies for one value, and
 * the cost lands on whoever writes the first ad-hoc query — {@code WHERE block_type = 'heading'}
 * returns nothing, from a database that plainly contains headings.
 */
@Converter(autoApply = false)
class DocBlockTypeConverter implements AttributeConverter<DocBlockType, String> {

    @Override
    public String convertToDatabaseColumn(DocBlockType type) {
        return type == null ? null : type.wire();
    }

    @Override
    public DocBlockType convertToEntityAttribute(String stored) {
        DocBlockType parsed = DocBlockType.parse(stored);
        if (stored != null && parsed == null) {
            // Loud, because the alternative is a null blockType rendering as an empty block. The
            // column has no CHECK constraint, so an unrecognised value is genuinely possible — from
            // a hand-written INSERT, or from a future type this release does not know.
            throw new IllegalStateException(
                    "doc_block.block_type holds '" + stored + "', which is not one of the six "
                            + "types the contract declares.");
        }
        return parsed;
    }
}
