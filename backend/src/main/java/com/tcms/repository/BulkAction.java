package com.tcms.repository;

/**
 * The eight operations {@code BulkOperation.action} offers.
 *
 * <p>Kept as a plain wire enum with no behaviour on it. What each one <em>means</em> lives in
 * {@link BulkCommand}, which is what the request parses into — and the split is the point: this
 * enum has eight values while {@code BulkCommand} has seven records, because
 * {@link #SUBMIT_FOR_REVIEW} is {@link #SET_STATUS} targeting {@code IN_REVIEW} and aliases to the
 * same command.
 *
 * <p>Plan §6g uses the submit vocabulary ("bulk-submit and bulk-approve are essential — nobody
 * approves 200 cases one at a time"), so the verb stays on the wire; aliasing it at the parse
 * boundary is what stops one state change having two implementations that drift.
 */
enum BulkAction {

    /** Attach one library condition to every selected case. */
    ATTACH_CONDITIONS,

    /** Remove one library condition from every selected case that carries it. */
    DETACH_CONDITIONS,

    /**
     * Move every selected case to a lifecycle status.
     *
     * <p><strong>The one action whose permission depends on its argument.</strong> Targeting
     * {@code APPROVED} needs {@code case:approve}; every other target needs {@code case:edit}. See
     * {@link BulkOperations#permission}.
     */
    SET_STATUS,

    /** Set the automation status. In the version content hash — see {@link BulkCommand}. */
    SET_AUTOMATION_STATUS,

    /** Assign an owner, or clear one. Validated against the project's member list (§54). */
    SET_OWNER,

    /** Add labels. Additive and set-valued; nothing is removed. */
    ADD_TAGS,

    /** {@link #SET_STATUS} targeting {@code IN_REVIEW}, under §6g's name for it. */
    SUBMIT_FOR_REVIEW,

    /** Move every selected case into one suite. In the version content hash. */
    MOVE_TO_SUITE
}
