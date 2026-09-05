package com.tcms.crm;

/**
 * Whether a client is one this company still works with.
 *
 * <p><strong>Not a soft-delete flag.</strong> {@code DELETE /clients/{clientId}} exists and really
 * deletes — it is how a record entered by mistake is removed — but it answers {@code 409} while any
 * project references the client, and {@link #ARCHIVED} is what that refusal points at (018 D-k).
 * Detaching those projects on the way past would silently leave live work with no customer, and this
 * product already decided the other way for projects: archiving hides and freezes, and nothing is
 * deleted.
 *
 * <p>Two members and no third. {@code clients.html}'s filter offers exactly these plus "all", and a
 * status nobody can select is a column nobody can act on.
 */
public enum ClientStatus {

    /** Working with them. The default, and what {@code GET /clients} lists when nothing asks. */
    ACTIVE,

    /**
     * Kept, but out of the way — hidden from the default list and from the project picker.
     *
     * <p><strong>Except on a project already attached to it</strong>, which keeps naming it:
     * {@code getProjectClientOptions} adds the project's own client back whatever its status, or a
     * picker that dropped it would detach on the next save.
     */
    ARCHIVED
}
