package com.tcms.billing;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The body of {@code POST /invoices} — a client, a period, and nothing else.
 *
 * <p><strong>There is no field here that could add a line by hand</strong>, and that absence is the
 * module's central claim rather than an omission: the lines are the approved hours that exist.
 * Story M2 states it — <em>billing is a report, not a spreadsheet</em> — and so does the mockup's
 * own subtitle.
 *
 * <p>{@link #termsDays} is nullable and the server records 30 when it is absent. Nullable rather
 * than defaulted <em>on the contract</em>: {@code openapi-typescript} emits a property with a
 * {@code default:} keyword as <em>required</em> in the generated type, which would make every draft
 * that omits it a compile error in both clients (018 T1's finding on {@code ClientInput.status}).
 */
public record InvoiceDraftInput(
        UUID clientId, LocalDate periodStart, LocalDate periodEnd, Integer termsDays) {
}
