package com.tcms.work;

import java.time.LocalDate;

/**
 * The contract's {@code CardAnnotation} — a {@code derived_item_annotation} row as the CALLER sees
 * it, and never as anybody else does.
 */
public record CardAnnotationView(String note, LocalDate personalDueDate, boolean flagged) {

    static CardAnnotationView of(CardAnnotation annotation) {
        return new CardAnnotationView(
                annotation.getNote(), annotation.getPersonalDueDate(), annotation.isFlagged());
    }
}
