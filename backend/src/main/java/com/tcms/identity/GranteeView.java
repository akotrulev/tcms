package com.tcms.identity;

/**
 * One person a refused reader can ask — a display name, and nothing else.
 *
 * <p>The whole of the design is what is absent. No id, no email, no role, no count. Design language
 * rule 7 asks a refusal to name <em>somebody who holds the permission</em>, and everything past a
 * name would turn a permission error into a way of reading the company's directory: an id is a
 * handle, an email is a contact, a role is a claim about authority. A name is the minimum that
 * answers "who do I ask", and the minimum is what this returns.
 *
 * <p>Not {@link com.tcms.api.UserRef}, deliberately, even though the assembly comes from one: that
 * shape carries the id and the email, and a record whose fields are the ones NOT being disclosed is
 * the point rather than duplication.
 */
public record GranteeView(String displayName) {
}
