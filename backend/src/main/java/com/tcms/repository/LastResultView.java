package com.tcms.repository;

import com.tcms.project.StatusKind;
import java.time.Instant;

/**
 * The newest recorded execution of one case — {@code TestCaseSummary.lastResult} in
 * {@code docs/specs/openapi.yaml}, and the value the case grid's <em>Last result</em> column and
 * the case detail page's result chip are drawn from.
 *
 * <h2>It is read from a read model, never from {@code test_execution}</h2>
 *
 * <p>{@code repository} may not import {@code execution} (plan §3, this package's
 * {@code package-info}), and a download of another module's rows on a request path is what that
 * rule exists to prevent. So the fact is derived on a schedule and on the
 * {@code execution.results_changed} event by {@link ExecutionFacts}, stored on {@code case_health},
 * and read back through {@link CaseHealth#lastResult()} — §3 lesson 4's shape, the same one
 * {@code suite_run_summary} has in {@code reporting}.
 *
 * <p><strong>This is why the type carries no execution id and no run id.</strong> Both are
 * available at derivation time and neither is on the wire: the contract declares four members, an
 * id here would invite a client to link to a resource in a module the caller reached this one
 * without touching, and a read model that carries more than its readers need is a read model whose
 * refresh cost grows for nobody. What the SPA does with this is render a status dot, an age and a
 * release — {@code CaseGrid.tsx} and {@code CaseDetailPage.tsx}.
 *
 * <h2>Absent, never half-populated</h2>
 *
 * <p>The contract makes the whole object nullable and every member of it required, and that is the
 * shape rather than an oversight — a case has either been executed or it has not, and there is no
 * state in between for a response to describe. {@link CaseHealth#lastResult()} therefore returns
 * null unless the stored moment is present, and the four columns behind it are written by one
 * statement from one row so that a partial answer cannot be assembled in the first place.
 *
 * <p>A null return serializes as an <em>omitted key</em> rather than an explicit null (see
 * {@code CaseRenderer} / the module's Jackson configuration), which the SPA reads as "Never run".
 * That reading has been correct all along; what was wrong until §166 is that it was the only
 * reading available, because the server sent the key for no case at all.
 *
 * @param statusKind the fixed aggregation class of the status somebody recorded, never the
 *              project's own name for it. A client that branched on the name would stop counting
 *              the day a project renamed "Blocked" to "Environment down" — the same argument
 *              {@link StatusKind} makes for reporting, arriving on a grid row.
 * @param executedAt when it was recorded, which is <strong>not</strong> when the run was created:
 *              §42 writes one empty execution per case the moment a run is planned, and a case in a
 *              planned run has not been executed. The derivation's predicate is
 *              {@code status_id IS NOT NULL}, the same definition of "recorded" the plan
 *              statistics and §52's suite timeline use.
 * @param releaseVersion <strong>nullable while the object is present</strong> — the one member that
 *              is. A run outside a release has no version, and the SPA's tooltip appends
 *              "· release X" only when it is there. Denormalized onto {@code test_execution} since
 *              V1, so the derivation copies it off the winning row rather than joining back through
 *              {@code test_run}.
 * @param configurationName the environment that execution ran under — <strong>never null while the
 *              object is present</strong>, because every run has exactly one configuration
 *              ({@code TestRun.configuration} is required and {@code configuration_id} is a
 *              {@code NOT NULL} foreign key). Served rather than left for every client to look up
 *              because it is the second half of the sentence the column exists to write: "Failed"
 *              on its own is a status, and "Failed on Chrome, passed on Firefox" is the reason a
 *              grid shows a last result at all.
 *              <p>The <em>name</em> and not an id, which is deliberate and is the opposite call to
 *              {@link #statusKind} one field up. A status name is renameable and its kind is the
 *              fixed class the contract declares, so the kind travels; a configuration has no such
 *              class, the contract declares a string, and a configuration is unique by name within
 *              a project — {@code PlanStatisticsView.ConfigurationCountsView} keys its rows the
 *              same way for the same reason.
 *              <p>Stored on {@code case_health} since {@code V72}, joined through the execution's
 *              run by the same statement that writes the three above.
 */
public record LastResultView(
        StatusKind statusKind,
        Instant executedAt,
        String releaseVersion,
        String configurationName) {
}
