package com.tcms.repository;

import com.tcms.api.UserRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code TestCaseSummary} in {@code docs/specs/openapi.yaml} — one row of the case grid.
 *
 * <p>A DTO for the reason every view here is one: {@code companyId} must never leave the building
 * (ERD §0.1 point 2), and {@code projectId} is already in the request path — a body field that can
 * disagree with the path is a field someone will eventually trust over the path.
 *
 * @param keySeq the numeric part of {@link #key}, on the wire because <strong>the client must not
 *               sort by the text</strong>: {@code TC-10000} sorts before {@code TC-9999}. The
 *               contract says so in its own description, and the SPA's grid orders on it.
 * @param suiteName the folder's display name, resolved server-side. The alternative — letting the
 *               SPA join it against the tree it already loaded — breaks the moment the grid is
 *               shown without the tree, and puts a second copy of "which suite is this" in the
 *               client.
 * @param owner  <strong>nullable</strong>. The contract originally marked this required while
 *               {@code test_case.owner_id} has always permitted null; this slice resolved the
 *               divergence toward the schema and {@code openapi.yaml} now says
 *               {@code nullable: true}. Listing an unowned case with no owner beats the two
 *               alternatives: hiding the row loses a case from its own project's list, and
 *               inventing an owner ("Unassigned" as a fake user) puts a person on the wire who does
 *               not exist and whose id nothing can resolve. Same rule as §24's title-less inline
 *               condition — the response does not make a claim the data never made.
 * @param createdBy <strong>who wrote the case, which is not {@link #owner}</strong> — that is who
 *               is responsible for it now, and the two diverge the moment a case is reassigned.
 *               This one is the field authorization turns on: {@code case:delete} is
 *               {@code OWN}-qualified for a Test Engineer (roles spec §A5) and
 *               {@code CaseDeleteService} compares {@code test_case.created_by}, so without this on
 *               the wire the SPA had no way to ask the question the server answers — the Delete
 *               button enabled on every case and the caller found out which were theirs by being
 *               refused. Nullable for the same reason the column is: a row written before
 *               authorship was recorded has no author, and
 *               {@code IdentityProjectAccess.canDoOnAuthored} answers such a row {@code false} for
 *               an {@code OWN} grant. Resolved through the <em>same</em> batch as {@link #owner} —
 *               see {@link CaseSummaries}, where a second lookup would be the N+1 that this class
 *               spends four bulk queries avoiding.
 * @param testType nullable — a case need not be classified, and nothing forces it to be
 * @param features every Feature value on the case, by name. A list because Feature is the one
 *               MULTI-cardinality dimension (plan §6d).
 * @param healthSignals derived, never authored, <del>and <strong>empty for every case
 *               today</strong> because nothing computes them yet</del> — nine of the ten have a
 *               derivation as of §178, and {@code unlinked} is raised on most cases of a project
 *               that has not started linking. See {@link HealthSignal}. Never null: the contract
 *               marks it required, so the SPA cannot null-check it.
 *
 *               <p><strong>Each entry carries its severity (§179) and the list is worst
 *               first.</strong> Both are here because the SPA had them, and had them wrong: a
 *               hand-kept {@code SEVERE_SIGNALS} list that disagreed with {@link HealthSignal} about
 *               two members, and a grid that renders {@code healthSignals[0]} as the row's chip —
 *               so a case both unlinked and failing showed the missing link and hid the failure.
 *               See {@link RaisedSignalView} and {@link CaseHealth#signals()}.
 * @param lastResult the case's newest recorded execution, or <strong>null when it has never been
 *               executed</strong>. Nullable rather than a fabricated {@code UNTESTED} result: the
 *               latter would invent an execution that never happened, and the SPA already has a
 *               different rendering for the absence ("Never run"). See {@link LastResultView}.
 *
 *               <p>Resolved from {@code case_health} — the row {@code healthSignals} is already
 *               read from, so it costs <strong>no additional query</strong> on a page of two
 *               hundred. That is a consequence of where the derivation was put and is worth
 *               stating, because the obvious alternative (a {@code case_last_result} table) would
 *               have made it a fifth bulk lookup for the same fact.
 *
 * <p><del>{@code lastResult} is deliberately not a field here</del> → <strong>it is one, since
 * §166, and both of the reasons this javadoc has given for its absence are now history worth
 * keeping.</strong> The first said the {@code execution} module "does not exist — nothing can
 * record a result, so no case has one", and concluded "it appears when {@code execution} does".
 * That module is 119 files; results have been recorded since §117 and ingested from CI since §119,
 * so <strong>the SPA rendered "Never run" for every case in the product</strong>, executed ones
 * included — {@code CaseGrid}'s Last result column, and the same value on
 * {@link TestCaseDetailView}'s page. §165 measured that and replaced the argument.
 *
 * <p>The second, §165's, was correct and is what this slice acted on: the obstacle is not
 * existence, it is the module boundary — {@code repository} may not import {@code execution} (see
 * this package's {@code package-info}, which stated it correctly the whole time), so the value has
 * to arrive as a read model fed by events, §3 lesson 4's shape, <strong>not</strong> a wiring fix
 * and not a wait. It does: {@code ExecutionFacts} derives it on the
 * {@code execution.results_changed} event and on the hourly schedule, and this field reads
 * {@code case_health}.
 *
 * <p>What holds unchanged from both: the contract does not mark it required, so omitting the key
 * for a case that has never run is legal, and serializing an explicit {@code null} would say the
 * same thing more expensively. The pair of entries in
 * {@code ContractConventionTest.MEMBER_DIVERGENCE_BY_DECISION} are <em>deleted</em> rather than
 * edited — that map is pinned in both directions, so a closed divergence whose entry stayed would
 * fail the build, which is the property §165 built it for.
 */
public record TestCaseSummaryView(
        UUID id,
        String key,
        long keySeq,
        String title,
        UUID suiteId,
        String suiteName,
        Priority priority,
        LifecycleStatus lifecycleStatus,
        AutomationStatus automationStatus,
        StepFormat stepFormat,
        UserRef owner,
        UserRef createdBy,
        String testType,
        String testLevel,
        List<String> features,
        List<String> tags,
        List<RaisedSignalView> healthSignals,
        // Positioned where the contract positions it — after healthSignals, before updatedAt.
        // Jackson serializes a record in declaration order, so a component out of place produces a
        // response whose key order does not match the schema it claims to implement. Nothing breaks;
        // it just makes every hand-comparison of the two documents harder for no reason.
        LastResultView lastResult,
        Instant updatedAt) {
}
