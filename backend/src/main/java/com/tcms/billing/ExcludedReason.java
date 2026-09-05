package com.tcms.billing;

/**
 * Why an hour in the period is <strong>not</strong> on a line — and both members are always looked
 * for (019 D-g).
 *
 * <p>A draft reports three populations and an hour is in exactly one of them: priced, or one of
 * these two. Neither is silently dropped, because an hour that vanished from an invoice with no
 * explanation is the defect this whole module exists to remove.
 *
 * <p><strong>Not a contract enum.</strong> {@code ExcludedHours.reason} is declared inline on that
 * schema rather than as a named component, so {@code ContractEnumTest} has no pairing to make and
 * this type carries no schema's name. The members are spelled exactly as the contract's inline
 * enumeration spells them, and the check that they agree is the serialized body, not a rule.
 */
public enum ExcludedReason {

    /**
     * Logged in the window, and its timesheet period is not {@code APPROVED}.
     *
     * <p>{@code invoice.html} states this one on the screen: <em>"38 hours are excluded because they
     * are not approved… They are counted here so nobody wonders where they went."</em>
     */
    NOT_APPROVED,

    /**
     * Approved, and the client's rate card has no entry effective that day — or there is no card.
     *
     * <p><strong>The bucket a naive implementation loses</strong>, because from inside a join it
     * looks exactly like a priced hour. {@code billing.html} says what it means:
     * <em>"Without a card, approved hours are still recorded — they just cannot be priced."</em>
     */
    NO_RATE
}
