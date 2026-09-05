package com.tcms.crm;

/**
 * The body of the contact writes.
 *
 * <p>A name is enough — {@code client.html}'s third contact has no phone, and that is a whole
 * contact.
 *
 * <p><strong>Four fields, none of them a credential.</strong> This is the contract's half of "a
 * contact never becomes a login": there is nothing to send, so no caller — a person, an agent, or a
 * future screen — can ask for one without the schema changing first, which is what makes reopening
 * scope D1 a visible act rather than a quiet one.
 */
public record ClientContactWriteRequest(String name, String role, String email, String phone) {
}
