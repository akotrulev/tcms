package com.tcms.crm;

import java.util.UUID;

/**
 * A client's fields as the service has validated them — the internal shape {@link Client#apply}
 * takes.
 *
 * <p>Separate from {@link ClientWriteRequest}, which is the wire shape: that one carries a nullable
 * {@code status} because the contract defaults it to {@code ACTIVE} when omitted, and this one
 * carries the resolved enum. Collapsing them would put "what the caller may leave out" and "what the
 * row must have" in one type, which is where a default quietly becomes a nullable column.
 */
record ClientEdit(
        String name,
        String location,
        String industry,
        String notes,
        ClientStatus status,
        UUID accountManagerId) {
}
