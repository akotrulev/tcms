package com.tcms.crm;

import java.util.List;

/**
 * {@code client.html} in one document — the client, its contacts and its projects.
 *
 * <p>All three together rather than three round trips, because that screen renders them together and
 * none of them is paged.
 *
 * <p><strong>No hours and no invoice figures.</strong> That mockup also draws an "Hours and invoices
 * to date" table, two tiles over it and a rate card panel beside it. All of them are M8's, and the
 * client page ships a named reserved region instead of a table with three of its five columns
 * missing (018 D-g). Story C3 words the deferral itself: <em>"its projects, contacts, and — once
 * time and billing exist — hours and invoices to date."</em>
 */
public record ClientDetailView(
        ClientView client,
        List<ClientContactView> contacts,
        List<ClientProjectView> projects) {
}
