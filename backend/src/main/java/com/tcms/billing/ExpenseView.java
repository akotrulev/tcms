package com.tcms.billing;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A recorded cost on the wire. <strong>Never an invoice line</strong> (scope D24, 019 D-j), and no
 * screen renders one — see {@link ExpenseController} for both reasons.
 */
public record ExpenseView(
        UUID id,
        UUID projectId,
        String projectName,
        LocalDate incurredOn,
        String description,
        Money amount) {
}
