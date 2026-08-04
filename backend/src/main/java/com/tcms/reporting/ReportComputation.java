package com.tcms.reporting;

import java.util.UUID;

/**
 * One report type — §6e's catalogue has ten and R3 builds the first.
 *
 * <p>A computation turns a project and a parameter object into a {@link ReportModel} and knows
 * nothing about formats, jobs, storage or HTTP. That is what lets the pipeline be tested without a
 * renderer and a renderer without a database, and it is the reason phase 3 of this slice is its own
 * commit.
 *
 * <h2>{@link #type()} is the wire string, not an enum</h2>
 *
 * <p>{@code report_job.type} and {@code report_definition.type} are both {@code TEXT} with no CHECK,
 * and the contract's example value is {@code aggregate_execution} — lowercase snake case. A saved
 * definition from six months ago must still name its report after a release renames a class, so the
 * string is the durable identifier and the class name is not. {@link ReportComputations} is what
 * makes an unknown one a refusal rather than a job that runs nothing.
 */
interface ReportComputation {

    /** The stable wire identifier, lowercase snake case. Never derived from the class name. */
    String type();

    /** A one-line description of what this report is, for the message that lists what exists. */
    String description();

    /**
     * Computes the model. Runs inside the caller's transaction, with the company already in context.
     *
     * @param projectId bounds every query. The gate that decided the caller may see this project has
     *     already run — this method does not re-decide it, and must not be given the chance to
     *     disagree with the one that did.
     * @param parameters what was asked for; refuses its own missing or malformed values
     * @throws IllegalArgumentException when the parameters do not describe something computable
     */
    ReportModel compute(UUID projectId, ReportParameters parameters);
}
