package com.tcms.project;

/**
 * The settings form's submission — a full replacement of all eight values.
 *
 * <p><strong>Boxed types, so "not sent" and "sent false" are distinguishable in this class</strong>
 * even though the service goes on to treat them alike. A primitive {@code boolean} field would make
 * an omitted {@code reviewEnabledCase} arrive as {@code false} indistinguishably from a deliberate
 * one, and the difference matters for the one field where it is not a boolean:
 * {@code requiredPeerReviews} omitted is a <em>field error</em>, not a silent 0, because 0 is a
 * meaningful and common value and a client that dropped the field would otherwise have silently
 * turned the requirement off.
 *
 * <p>The three booleans genuinely do collapse — an absent opt-in is "off", which is both §6g's
 * default and the safe reading — and that asymmetry is deliberate rather than an oversight: a
 * missing switch means the feature is off, a missing number means the request is incomplete.
 *
 * <p>A {@code PUT}, so §74's absent/null/empty rules do not apply. Nothing here is a list and
 * nothing here is clearable, so "leave this one alone" has no meaning a user could ask for.
 *
 * <p>The two gating switches collapse the same way the three opt-ins do, and for a stronger reason:
 * §6g calls gating <em>"powerful and genuinely obstructive"</em> and requires it to be an explicit
 * choice, so a client that dropped the field must land on <em>off</em>. An absent switch that turned
 * a gate <em>on</em> would be the one reading of a missing field that can stop a release.
 */
public record ProjectSettingsRequest(
        Boolean reviewEnabledCase,
        Boolean reviewEnabledSuite,
        Boolean reviewEnabledPlan,
        Integer requiredPeerReviews,
        Boolean authorMayApproveOwnWork,
        String editAfterApproval,
        Boolean requireApprovedCaseForRun,
        Boolean requireApprovedPlanToStart) {
}
