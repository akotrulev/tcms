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
 * @param healthSignals derived, never authored, and <strong>empty for every case today</strong>
 *               because nothing computes them yet — see {@link HealthSignal}. Never null: the
 *               contract marks it required, so the SPA cannot null-check it.
 *
 * <p><strong>{@code lastResult} is deliberately not a field here — and the reason below is §165's,
 * because the one this javadoc gave had expired.</strong> It said the {@code execution} module
 * "does not exist — nothing can record a result, so no case has one", and it concluded "it appears
 * when {@code execution} does". That module is 119 files; results have been recorded since §117 and
 * ingested from CI since §119. <strong>The SPA has therefore been rendering "Never run" for every
 * case in the product, including ones with executions</strong> — {@code CaseGrid}'s Last result
 * column, and the same value on {@link TestCaseDetailView}'s page.
 *
 * <p>The live obstacle is not existence, it is the module boundary: {@code repository} may not
 * import {@code execution} (see this package's {@code package-info}, which states it correctly and
 * always did — "the same rule is what keeps {@code TestCaseSummary.lastResult} off the wire today
 * rather than reaching for {@code test_execution} directly"). Closing it is a read model fed by
 * events, §3 lesson 4's shape, <strong>not</strong> a wiring fix and not a wait.
 *
 * <p>What holds unchanged: the contract does not mark it required, so omitting the key is legal;
 * serializing an explicit {@code null} would say the same thing more expensively; serializing a
 * fabricated {@code UNTESTED} result would invent an execution that never happened. Pinned as
 * CONTRACT-ONLY in {@code ContractConventionTest.MEMBER_DIVERGENCE_BY_DECISION}, which fails the
 * build if the contract drops the property or this record grows the component.
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
        List<HealthSignal> healthSignals,
        Instant updatedAt) {
}
