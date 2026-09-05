package com.tcms.billing;

import java.math.BigDecimal;

/**
 * Hours in the period that are <strong>not</strong> on a line, counted and named (019 D-g).
 *
 * <p>Both buckets are always looked for and neither is ever silently dropped. {@code invoice.html}
 * draws the first as an amber note above the lines — <em>"38 hours are excluded because they are not
 * approved… They are counted here so nobody wonders where they went"</em> — and
 * {@code billing.html} states the second: <em>"Without a card, approved hours are still recorded —
 * they just cannot be priced."</em>
 *
 * <p><strong>No amount and no currency</strong>, because neither bucket has one. An unapproved hour
 * has no price because nobody has signed for it; an unpriceable hour has no price because no rate
 * card entry was effective that day. A zero there would read as <em>worth nothing</em>.
 *
 * <p>{@link #detail} is the who-and-how-much the note renders — {@code Tomas Ek 24h · Jun Park 14h}
 * — resolved server-side for {@link InvoiceLineView#appliesTo}'s reason: one name rule, in one
 * place.
 */
public record ExcludedHoursView(ExcludedReason reason, BigDecimal hours, String detail) {
}
