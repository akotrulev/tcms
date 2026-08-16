package com.tcms.execution;

import java.util.UUID;

/**
 * {@code TestRunCloneInput} on the wire — the new run's name and the four things a re-run changes.
 *
 * <p>Plan §6v calls clone-run the highest-value of the three, and the sentence it uses is the design
 * brief: <em>re-running a regression cycle against a new build</em>. A clone that could not change
 * the build number would make the caller edit the run immediately afterwards, so
 * {@code releaseVersion}, {@code buildNumber} and {@code configurationId} are overridable and fall
 * back to the source's.
 *
 * <h2>{@code assigneeId} does NOT fall back, because there is nothing to fall back to</h2>
 *
 * <p>§6v's table says a cloned run copies its "assignees". <strong>A run has none.</strong> The
 * assignee is a column on {@code test_execution}, one per case, so a checklist of two hundred rows
 * can name two hundred different people — and picking one of them to be "the run's assignee" would
 * be inventing a fact the schema deliberately does not hold. Omitted means an unassigned checklist,
 * which is what a fresh run of the same scope actually is; supplying one assigns the whole checklist,
 * which is exactly what {@code RunWriteRequest} already does on a create.
 *
 * <h2>There is no {@code overrideApprovalGate} fallback either, and that one is a rule</h2>
 *
 * <p>The override is <em>a statement about a request</em> and not a property of the run —
 * {@link RunWriteRequest}'s own javadoc says so, and nothing on {@code test_run} stores it. So it
 * cannot be inherited even by accident, and this record takes it fresh: cloning last month's
 * overridden run into this month's build re-evaluates §6g's gate from scratch and refuses unless the
 * cases are approved now, or the caller overrides again, on the record. A clone that carried the
 * flag forward would be a laundered override — §6v rule 2's argument about review state, applied to
 * the gate instead.
 *
 * @param name required, and the server invents no {@code "Copy of "}: two rows a person cannot tell
 *             apart in a list is the failure that default ships, and the prefix's author also has to
 *             decide what happens at the length limit. The SPA prefills the suggestion in its
 *             dialog, where a human sees it before it becomes a row. Validated by
 *             {@link RunEdit}, so "what is an acceptable run name" keeps having one answer
 */
public record RunCloneRequest(
        String name,
        UUID configurationId,
        String releaseVersion,
        String buildNumber,
        UUID assigneeId,
        Boolean overrideApprovalGate,

        /**
         * Re-run what the source was <strong>asked</strong> for, rather than what it materialised
         * (B7). Absent or false is the old behaviour and stays the default.
         *
         * <p>A clone normally re-runs the source's exact case list, which is right: a re-run of
         * "the regression pass we did on Friday" is that pass again, and a set that quietly grew
         * would make two runs with one name cover different work. What that cannot express is the
         * other intention — <em>run the smoke suite again</em> — where a case added to the suite
         * since Friday is precisely what should be included, and until this flag the only way to
         * get it was to create a new run by hand and lose the link to the source.
         *
         * <p>Requires the source to have a recorded request ({@code V61}), and requires it to have
         * <em>arms</em>: a run whose scope was a bare list of case ids re-resolves to the same list,
         * so asking for it is accepted and changes nothing. A run created before {@code V61}, or by
         * ingestion, has no request at all and the clone is refused with a field error rather than
         * silently falling back — falling back would answer a different question from the one asked
         * and look identical afterwards.
         */
        Boolean rescope) {
}
