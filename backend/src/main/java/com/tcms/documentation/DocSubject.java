package com.tcms.documentation;

import java.util.UUID;

/**
 * What a page is <strong>about</strong>, when it is about another artifact rather than free-standing
 * — {@code doc_page.subject_type} / {@code subject_id}, as one value.
 *
 * <h2>A pair, not two parameters</h2>
 *
 * <p>{@code V29} spells the pairing as a {@code CHECK} because a type with no id is a page claiming
 * to be about something it cannot name, and an id with no type is an id nobody can tell which table
 * belongs to. This record is that constraint arriving one layer earlier: the two values are never
 * apart, so no caller can hand over half of a subject, and {@code null} means <em>free-standing</em>
 * — which is nearly every page in the product.
 *
 * <h2>Opaque, and it has to be</h2>
 *
 * <p>{@link #id} names a row in a table this module may not import. {@code documentation} may depend
 * only on {@code tenancy}, {@code identity} and {@code project}, and the modules owning the
 * documentable artifacts are not among them — so this is an id and a kind, checked through the
 * {@code *Facts}-shaped seams ({@link PlanFacts}) and never resolved into a foreign entity here.
 *
 * <p>{@code project.Review}'s subject triple is the same decision for the same reason. The
 * difference is that a review also stores the subject's <em>version</em>, because a review is of a
 * thing as it stood; a document is of the thing itself and follows it.
 */
record DocSubject(DocSubjectType type, UUID id) {

    /** The wire form of {@link #type}, for the view. */
    String typeName() {
        return type.name();
    }
}
