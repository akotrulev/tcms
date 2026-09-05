package com.tcms.crm;

import com.tcms.api.UserRef;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code Client} on the wire.
 *
 * <p>{@link #projectCount} counts <strong>archived projects too</strong>, and that is the same
 * decision {@link ClientRepository#countProjectsNaming} records: it is the number
 * {@code DELETE /clients/{clientId}} refuses on, so a count that quietly excluded them would show a
 * zero beside a delete the server then answers {@code 409} to.
 *
 * <p>No rate, no cost, no invoice value, no hours figure. See the package comment.
 */
public record ClientView(
        UUID id,
        String name,
        String location,
        String industry,
        String notes,
        ClientStatus status,
        UserRef accountManager,
        int projectCount,
        int contactCount,
        Instant createdAt) {
}
