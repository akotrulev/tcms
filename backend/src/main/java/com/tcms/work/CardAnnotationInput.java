package com.tcms.work;

import java.time.LocalDate;

/**
 * The contract's {@code CardAnnotationInput}. Every field optional; an absent key is "leave it".
 *
 * <p>{@code flagged} is a {@code Boolean} rather than a {@code boolean} so that "not sent" and
 * "sent as false" are different values here — a primitive would make every note edit silently clear
 * the flag.
 */
public record CardAnnotationInput(String note, LocalDate personalDueDate, Boolean flagged) {
}
