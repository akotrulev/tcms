package com.tcms.staffing;

/**
 * Why somebody is away.
 *
 * <p>{@code HOLIDAY} is how a public holiday is recorded, because there is no holiday calendar in
 * this product (017 D-c) and a per-person row is what actually zeroes capacity. A company-wide
 * holiday is therefore <em>n</em> rows, one per person — the cost is stated rather than discovered,
 * and it is the right trade while the product has no locale model: a shared calendar in a system
 * serving several client companies from one instance would have to be per company <em>and</em> per
 * country, and neither exists.
 *
 * <p>The kind changes nothing about the arithmetic. Every member subtracts the same hours, and it
 * is on the wire so a person reading the timeline can tell leave from sickness — not so a total can
 * be computed differently for one of them.
 */
public enum AbsenceKind {
    LEAVE,
    SICK,
    HOLIDAY,
    OTHER
}
