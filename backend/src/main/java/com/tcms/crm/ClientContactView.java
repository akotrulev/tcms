package com.tcms.crm;

import java.util.UUID;

/**
 * {@code ClientContact} on the wire — contact information, and <strong>not an identity</strong>.
 *
 * <p>There is no {@code userId} on this record and there never will be one without reopening scope
 * D1. A reader checking whether a contact is an account should find the answer by reading this
 * five-field record and finding nothing that could be one.
 */
public record ClientContactView(UUID id, String name, String role, String email, String phone) {
}
