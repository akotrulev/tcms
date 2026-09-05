package com.tcms.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One approved hour behind a line — {@code invoice.html}'s <em>Time entries behind these lines</em>
 * table.
 *
 * <h2>{@link #periodApprovedAt} and {@link #periodApprovedBy} say PERIOD, and the names are the
 * decision</h2>
 *
 * <p>019 D-f, inherited from 016 D-i. A timesheet period is per person, per fortnight, and it
 * <strong>spans projects</strong>, while {@code time:approve} is a PROJECT-scope grant — so
 * approving a period approves entries against projects the approver cannot see. That makes
 * <em>"these hours were approved"</em> a claim the data supports and <em>"somebody with authority
 * over this project approved these hours"</em> a claim it does not, and <strong>no field here makes
 * the second</strong>. The mockup's column is headed <em>Approved</em> and reads <em>3 Sep ·
 * Rae</em>, which is exactly what this is: the decision on that person's fortnight.
 *
 * <p><em>Rejected:</em> evaluating {@code time:approve} for the deciding approver against each
 * entry's project at invoice time and flagging the failures. It computes a claim about the past from
 * the grants of the present — a person who has since left the project would make last month's
 * approved hours retroactively suspect — which is a worse statement than the one we decline to make.
 * If per-project approval authority is ever wanted it belongs in {@code time} as a column written at
 * decision time, and reopening 016 D-i is how to get it.
 *
 * <p>{@link #periodApprovedBy} is a <strong>name</strong> and not a user id, because on an issued
 * invoice it is a snapshot: the document must read correctly after the approver leaves.
 *
 * <p>Every entry here is on a line. The mockup draws a fifth row reading <em>awaiting</em> for an
 * unapproved hour; the contract makes both approval fields required, so an unapproved hour is not
 * representable on this table and reaches the screen through {@link ExcludedHoursView} instead —
 * counted and named, which is what 019 D-g asks and what the mockup's own amber note describes.
 */
public record InvoiceEntryView(
        UUID timeEntryId,
        LocalDate spentOn,
        String personName,
        BigDecimal hours,
        String subjectKey,
        String subjectTitle,
        Instant periodApprovedAt,
        String periodApprovedBy) {
}
