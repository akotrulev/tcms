package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.Role;
import com.tcms.identity.RoleAssignment;
import com.tcms.identity.RoleAssignmentRepository;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handing a run's checklist out in one act — <strong>and the first writer of
 * {@code audit_event.bulk_operation_id} in this product's history.</strong>
 *
 * <p>Three things here are not obvious and each has an arm of its own.
 *
 * <ol>
 *   <li><strong>Determinism is this operation's idempotency.</strong> There is no
 *       {@code Idempotency-Key} — nothing is created, so there would be no resource to answer a
 *       replay with. Instead the split is a pure function of the run's row order and the caller's
 *       assignee order, and an assignment that changes nothing writes no audit row, so a replay is
 *       a no-op that can be asserted rather than assumed.</li>
 *   <li><strong>A bad assignee refuses the act; a stale execution id skips one row.</strong> That
 *       is {@code BulkOperation}'s rule — knowable before any row is read versus a property of the
 *       row — and getting it backwards is how a filtered selection of four hundred becomes
 *       unusable because one row went away.</li>
 *   <li><strong>One audit row per target, sharing one id.</strong> Every other bulk service in this
 *       codebase writes ONE row with a {@code target_ids} array. That shape cannot state what a
 *       distribute did, because one row has one {@code changes} document and this act moves rows to
 *       several people.</li>
 * </ol>
 *
 * <p>Two departures from the plan that wrote this class, both forced by what the code and the
 * catalogue actually say:
 *
 * <ul>
 *   <li><strong>The caller who lacks {@code run:execute} is a Viewer, not a Test Engineer.</strong>
 *       Roles spec §A3 and {@code V3} both grant {@code run:execute} to Test Engineer — working a
 *       run by hand is that role's whole job — so a Test Engineer is not a refusal at all. Viewer is
 *       the assignable role in the catalogue that holds {@code project:read} and none of the run
 *       keys.</li>
 *   <li><strong>The runs are instance fields, not id constants.</strong> The fixture builds them
 *       through {@code POST /runs}, which mints its own ids: a test that inserted rows to pin an id
 *       would be proving the query works over data the product does not produce.</li>
 * </ul>
 *
 * <p>Ids use the {@code 88…} prefix, pre-assigned to this area and checked against every
 * {@code UUID.fromString} literal under {@code src/test/java} on 2026-08-22 — handoff §19's fixture
 * collision has happened five times and two lanes taking one prefix merge clean.
 */
@AutoConfigureMockMvc
@DisplayName("Bulk execution assignment — one act, and the trail that can still be read afterwards")
class ExecutionBulkAssignTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("88000000-0000-4000-8000-00000000000a");
    /** A second company. Its member drives the cross-tenant assignee arm. */
    private static final UUID RIVAL = UUID.fromString("88000000-0000-4000-8000-00000000000b");

    private static final UUID ATLAS = UUID.fromString("88001000-0000-4000-8000-000000000001");

    /** Test Lead on ATLAS: holds {@code run:execute}. */
    private static final UUID LEAD = UUID.fromString("88003000-0000-4000-8000-000000000001");
    /** Five testers, in the order a coordinator would type them. */
    private static final UUID ANA = UUID.fromString("88003000-0000-4000-8000-000000000002");
    private static final UUID BEN = UUID.fromString("88003000-0000-4000-8000-000000000003");
    private static final UUID CHO = UUID.fromString("88003000-0000-4000-8000-000000000004");
    private static final UUID DEV = UUID.fromString("88003000-0000-4000-8000-000000000005");
    private static final UUID EVE = UUID.fromString("88003000-0000-4000-8000-000000000006");
    /** An ACTIVE member of RIVAL and of nothing in ACME. */
    private static final UUID STRANGER = UUID.fromString("88003000-0000-4000-8000-000000000007");
    /** INVITED in ACME and never accepted — present, and not a member for this purpose. */
    private static final UUID INVITEE = UUID.fromString("88003000-0000-4000-8000-000000000008");
    /** Viewer on ATLAS, holding no {@code run:execute} — see the class header. */
    private static final UUID ONLOOKER = UUID.fromString("88003000-0000-4000-8000-000000000009");

    private static final UUID PAYMENTS = UUID.fromString("88004000-0000-4000-8000-000000000001");

    /** An execution id that names nothing anywhere, for the skip arm. */
    private static final UUID NOWHERE = UUID.fromString("8800ffff-0000-4000-8000-00000000ffff");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private com.tcms.auth.SessionTokens tokens;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private CompanyMembershipRepository memberships;

    @Autowired
    private RoleAssignmentRepository roleAssignments;

    @Autowired
    private TransactionTemplate tx;

    /** The twelve cases, in the order they were created — which is the run's own order. */
    private final List<UUID> theCases = new ArrayList<>();

    private UUID theRun;
    private UUID theClosedRun;
    /** The one execution of {@link #theClosedRun}, for the foreign-id and freeze arms. */
    private UUID theFrozenExecution;

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "ba-acme", "Acme", now);
            company(RIVAL, "ba-rival", "Rival", now);
            activeUser(LEAD, "lead@ba-acme.test", "L. Lead", now);
            activeUser(ANA, "ana@ba-acme.test", "A. Ana", now);
            activeUser(BEN, "ben@ba-acme.test", "B. Ben", now);
            activeUser(CHO, "cho@ba-acme.test", "C. Cho", now);
            activeUser(DEV, "dev@ba-acme.test", "D. Dev", now);
            activeUser(EVE, "eve@ba-acme.test", "E. Eve", now);
            activeUser(STRANGER, "stranger@ba-rival.test", "S. Stranger", now);
            activeUser(INVITEE, "invitee@ba-acme.test", "I. Invitee", now);
            activeUser(ONLOOKER, "onlooker@ba-acme.test", "O. Onlooker", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ATLAS, ACME, "BAATL", "Atlas", now);
            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ANA, "A. Ana", now);
            member(ACME, BEN, "B. Ben", now);
            member(ACME, CHO, "C. Cho", now);
            member(ACME, DEV, "D. Dev", now);
            member(ACME, EVE, "E. Eve", now);
            member(ACME, ONLOOKER, "O. Onlooker", now);
            // Invited and never accepted: present in the table, and not a member for this
            // purpose. An unaccepted invitation must not become a way to be handed a client's work.
            invited(ACME, INVITEE, "I. Invitee", now);
            grant("lead-atlas", ACME, Role.TEST_LEAD_ID, LEAD, ATLAS, now);
            grant("onlooker-atlas", ACME, Role.VIEWER_ID, ONLOOKER, ATLAS, now);
        }));

        inCompany(RIVAL, () -> tx.executeWithoutResult(status ->
                member(RIVAL, STRANGER, "S. Stranger of Rival", now)));

        seedCompanyDefaults(ACME);
        clearMutableRows();
        seedTree();

        theCases.clear();
        for (int index = 1; index <= 12; index++) {
            theCases.add(createCase("Case " + index));
        }

        theRun = createRun("Regression", theCases);
        theClosedRun = createRun("Finished", List.of(theCases.get(0)));
        theFrozenExecution = executionsOf(theClosedRun).get(0);
        close(theClosedRun);

        forgetAssignmentAudit();
    }

    // =====================================================================
    // ASSIGN
    // =====================================================================

    @Test
    @DisplayName("assigning a selection puts every row on that person")
    void aStraightAssignMovesEveryRow() throws Exception {
        List<UUID> rows = executionsOf(theRun);

        JsonNode result = commit(assign(rows, ANA, false));

        assertThat(result.get("total").asInt()).isEqualTo(12);
        assertThat(result.get("dryRun").asBoolean()).isFalse();
        assertThat(result.get("unchanged")).isEmpty();
        assertThat(result.get("skipped")).isEmpty();
        assertThat(result.get("changed")).hasSize(12);
        assertThat(result.get("bulkOperationId").isNull())
                .as("a commit that moved rows names the operation its audit rows share")
                .isFalse();

        for (JsonNode change : result.get("changed")) {
            assertThat(change.get("assigneeId").asText()).isEqualTo(ANA.toString());
            assertThat(change.get("assigneeName").asText()).isEqualTo("A. Ana");
            assertThat(change.get("caseKey").asText()).startsWith("TC-");
        }

        // The COLUMN, not just the response: a service that answered correctly and wrote nothing
        // passes a body-only assertion.
        assertThat(assigneesOf(theRun).values()).allMatch(ANA::equals);
    }

    @Test
    @DisplayName("a preview writes nothing at all")
    void aDryRunChangesNothing() throws Exception {
        List<UUID> rows = executionsOf(theRun);

        JsonNode explicit = commit(assign(rows, ANA, true));
        assertThat(explicit.get("dryRun").asBoolean()).isTrue();
        assertThat(explicit.get("changed")).hasSize(12);
        assertThat(explicit.get("bulkOperationId").isNull())
                .as("nothing happened, so there is no operation to name").isTrue();

        // And again with dryRun ABSENT. The contract defaults it to true, and an absent field
        // defaulting to FALSE is the one mistake here that destroys data.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "ASSIGN");
        body.put("executionIds", ids(rows));
        body.put("payload", Map.of("assigneeId", ANA.toString()));
        JsonNode implied = commit(body);
        assertThat(implied.get("dryRun").asBoolean())
                .as("an omitted dryRun is a PREVIEW; defaulting it to false commits an act "
                        + "nobody confirmed")
                .isTrue();

        assertThat(assigneesOf(theRun).values()).allMatch(java.util.Objects::isNull);
        assertThat(assignmentAudit()).isEmpty();
    }

    @Test
    @DisplayName("a null assignee takes every selected row off everybody")
    void assigningNobodyUnassigns() throws Exception {
        List<UUID> rows = executionsOf(theRun);
        commit(assign(rows, ANA, false));
        forgetAssignmentAudit();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "ASSIGN");
        body.put("executionIds", ids(rows));
        body.put("dryRun", false);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assigneeId", null);
        body.put("payload", payload);

        JsonNode result = commit(body);

        assertThat(result.get("changed")).hasSize(12);
        for (JsonNode change : result.get("changed")) {
            assertThat(change.get("assigneeId").isNull()).isTrue();
            assertThat(change.get("assigneeName").isNull()).isTrue();
        }
        assertThat(assigneesOf(theRun).values()).allMatch(java.util.Objects::isNull);

        List<AuditRow> trail = assignmentAudit();
        assertThat(trail).hasSize(12);
        assertThat(trail).allMatch(row -> "execution.unassigned".equals(row.action()));
        assertThat(trail).allMatch(row -> row.changes().contains("\"from\": \"" + ANA + "\"")
                || row.changes().contains("\"from\":\"" + ANA + "\""));
        assertThat(trail).allMatch(row -> row.changes().replace(" ", "").contains("\"to\":null"));
    }

    @Test
    @DisplayName("rows already on that person come back as unchanged and write no audit row")
    void anAlreadyAssignedRowIsUnchanged() throws Exception {
        List<UUID> rows = executionsOf(theRun);
        commit(assign(rows, ANA, false));
        int written = assignmentAudit().size();
        assertThat(written).isEqualTo(12);

        JsonNode again = commit(assign(rows, ANA, false));

        assertThat(again.get("changed")).isEmpty();
        assertThat(again.get("unchanged")).hasSize(12);
        assertThat(again.get("bulkOperationId").isNull())
                .as("no rows were written, so naming a group with no members would be a lie")
                .isTrue();
        assertThat(assignmentAudit()).hasSize(written);
    }

    // =====================================================================
    // DISTRIBUTE
    // =====================================================================

    @Test
    @DisplayName("distributing twelve rows across five people splits them 3/3/2/2/2")
    void distributeRoundRobinsInRunOrder() throws Exception {
        List<UUID> rows = executionsOf(theRun);
        List<UUID> five = List.of(ANA, BEN, CHO, DEV, EVE);

        JsonNode result = commit(distribute(rows, five, false));

        assertThat(result.get("changed")).hasSize(12);
        Map<UUID, UUID> landed = assigneesOf(theRun);
        for (int index = 0; index < rows.size(); index++) {
            assertThat(landed.get(rows.get(index)))
                    .as("row %d of the run's own order", index)
                    .isEqualTo(five.get(index % five.size()));
        }

        Map<UUID, Long> tally = new LinkedHashMap<>();
        landed.values().forEach(id -> tally.merge(id, 1L, Long::sum));
        assertThat(tally.get(ANA)).isEqualTo(3);
        assertThat(tally.get(BEN)).isEqualTo(3);
        assertThat(tally.get(CHO)).isEqualTo(2);
        assertThat(tally.get(DEV)).isEqualTo(2);
        assertThat(tally.get(EVE))
                .as("the FIRST TWO in the caller's list hold the remainder — that order is the "
                        + "coordinator's statement and the server never sorts it")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the split does not depend on the order the caller listed the executions in")
    void theSelectionIsASetAndTheRunDecidesTheOrder() throws Exception {
        List<UUID> rows = executionsOf(theRun);
        List<UUID> five = List.of(ANA, BEN, CHO, DEV, EVE);

        List<UUID> shuffled = new ArrayList<>(rows);
        java.util.Collections.shuffle(shuffled, new java.util.Random(88));

        commit(distribute(shuffled, five, false));

        Map<UUID, UUID> landed = assigneesOf(theRun);
        for (int index = 0; index < rows.size(); index++) {
            assertThat(landed.get(rows.get(index)))
                    .as("a service round-robining over the REQUEST array would give two "
                            + "coordinators different splits of the same selection, and neither "
                            + "could tell")
                    .isEqualTo(five.get(index % five.size()));
        }
    }

    @Test
    @DisplayName("a repeat of the same distribute changes nothing and writes no second operation")
    void aRetryChangesNothingAndWritesNoSecondOperation() throws Exception {
        List<UUID> rows = executionsOf(theRun);
        List<UUID> five = List.of(ANA, BEN, CHO, DEV, EVE);

        commit(distribute(rows, five, false));
        List<AuditRow> afterFirst = assignmentAudit();
        assertThat(afterFirst).hasSize(12);

        JsonNode replay = commit(distribute(rows, five, false));

        assertThat(replay.get("changed")).isEmpty();
        assertThat(replay.get("unchanged")).hasSize(12);
        assertThat(replay.get("bulkOperationId").isNull()).isTrue();
        assertThat(assignmentAudit())
                .as("THIS IS THE ARM THAT REPLACES AN IDEMPOTENCY KEY. If it fails, the "
                        + "determinism argument in the contract is false and the endpoint needs a "
                        + "different answer, not a patched test.")
                .hasSize(afterFirst.size());
    }

    @Test
    @DisplayName("more people than rows leaves the tail with nothing, and says so")
    void moreAssigneesThanRowsIsLegal() throws Exception {
        List<UUID> rows = executionsOf(theRun).subList(0, 3);
        List<UUID> five = List.of(ANA, BEN, CHO, DEV, EVE);

        JsonNode result = commit(distribute(rows, five, false));

        assertThat(result.get("total").asInt()).isEqualTo(3);
        assertThat(result.get("changed")).hasSize(3);
        Map<UUID, UUID> landed = assigneesOf(theRun);
        assertThat(landed.get(rows.get(0))).isEqualTo(ANA);
        assertThat(landed.get(rows.get(1))).isEqualTo(BEN);
        assertThat(landed.get(rows.get(2))).isEqualTo(CHO);
        assertThat(landed.values()).doesNotContain(DEV, EVE);
    }

    // =====================================================================
    // refusals, and the one thing that is a skip instead
    // =====================================================================

    @Test
    @DisplayName("an assignee who is not an active member of this company refuses the whole act")
    void aStrangerIsAFieldErrorAndNothingIsWritten() throws Exception {
        List<UUID> rows = executionsOf(theRun);
        commit(assign(rows, ANA, false));
        forgetAssignmentAudit();

        MvcResult refused = attempt(distribute(rows, List.of(ANA, STRANGER), false));
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        JsonNode errors = body(refused).get("errors");
        assertThat(errors.has("payload.assigneeIds"))
                .as("the fault is keyed to the control the coordinator can fix; %s", errors)
                .isTrue();

        // An unaccepted invitation must not become a way to be handed a client's work.
        MvcResult invited = attempt(distribute(rows, List.of(ANA, INVITEE), false));
        assertThat(invited.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(invited).get("errors").has("payload.assigneeIds")).isTrue();

        // ***Nothing is written*** — one transaction is what makes this true.
        assertThat(assigneesOf(theRun).values()).allMatch(ANA::equals);
        assertThat(assignmentAudit()).isEmpty();
    }

    @Test
    @DisplayName("an execution id from another run is skipped, and the rest of the act proceeds")
    void aForeignExecutionIdSkipsOneRow() throws Exception {
        List<UUID> rows = new ArrayList<>(executionsOf(theRun));
        rows.add(theFrozenExecution);
        rows.add(NOWHERE);

        JsonNode result = commit(assign(rows, ANA, false));

        assertThat(result.get("total").asInt()).isEqualTo(14);
        assertThat(result.get("changed")).hasSize(12);
        assertThat(result.get("skipped")).hasSize(2);
        List<String> skipped = new ArrayList<>();
        for (JsonNode row : result.get("skipped")) {
            skipped.add(row.get("executionId").asText());
            assertThat(row.get("reason").asText())
                    .as("a reason a dialog can print, not a code")
                    .isNotBlank();
        }
        assertThat(skipped)
                .as("this is the asymmetry with the stranger arm and the one most likely to be "
                        + "'simplified' into a 404")
                .containsExactlyInAnyOrder(theFrozenExecution.toString(), NOWHERE.toString());
        assertThat(assigneesOf(theRun).values()).allMatch(ANA::equals);
    }

    @Test
    @DisplayName("a caller without run:execute is refused, and it is the same key the single row takes")
    void aCallerWithoutRunExecuteIsRefused() throws Exception {
        List<UUID> rows = executionsOf(theRun);

        MvcResult refused = mvc.perform(MockMvcRequestBuilders
                        .post(bulkPath(theRun))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(ONLOOKER, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(assign(rows, ANA, false))))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        // Asserting the WRITE too, because a service that answered 403 after applying is the shape
        // a gate placed after the work produces.
        assertThat(assigneesOf(theRun).values()).allMatch(java.util.Objects::isNull);
        assertThat(assignmentAudit()).isEmpty();
    }

    @Test
    @DisplayName("a run that has ended refuses with 409")
    void aClosedRunIsFrozen() throws Exception {
        MvcResult refused = mvc.perform(MockMvcRequestBuilders
                        .post(bulkPath(theClosedRun))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                assign(List.of(theFrozenExecution), ANA, false))))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(refused).get("detail").asText())
                .as("the message names reopening, because that is the way forward")
                .contains("Reopen");
        assertThat(assignmentAudit()).isEmpty();
    }

    // =====================================================================
    // the trail
    // =====================================================================

    @Test
    @DisplayName("every audit row of one act shares one bulkOperationId, and different acts do not")
    void oneActIsOneBulkOperationId() throws Exception {
        List<UUID> rows = executionsOf(theRun);

        JsonNode first = commit(distribute(rows, List.of(ANA, BEN, CHO, DEV, EVE), false));
        List<AuditRow> afterFirst = assignmentAudit();
        assertThat(afterFirst).hasSize(12);
        assertThat(afterFirst).allMatch(row -> row.bulkOperationId() != null);
        assertThat(afterFirst.stream().map(AuditRow::bulkOperationId).distinct().toList())
                .hasSize(1);
        assertThat(afterFirst.get(0).bulkOperationId().toString())
                .as("the join between the result and the trail, which the audit screen and every "
                        + "future digest read")
                .isEqualTo(first.get("bulkOperationId").asText());

        JsonNode second = commit(assign(rows, ANA, false));
        List<AuditRow> all = assignmentAudit();
        assertThat(all)
                .as("""
                        21, not 24 — and the difference IS this class's determinism rule. The \
                        distribute above put rows 1, 6 and 11 on ANA (indices 0, 5 and 10 of the \
                        3/3/2/2/2 spread), so assigning all twelve to ANA finds three already in \
                        the requested state; those are `unchanged` and write NO audit row, which \
                        is what makes a replay of this endpoint write nothing at all. Expecting 24 \
                        would be asserting that an unchanged row is audited, i.e. the exact \
                        behaviour `aRetryChangesNothingAndWritesNoSecondOperation` forbids.""")
                .hasSize(21);
        List<UUID> operations = all.stream()
                .map(AuditRow::bulkOperationId).distinct().toList();
        assertThat(operations).hasSize(2);
        assertThat(second.get("bulkOperationId").asText())
                .isNotEqualTo(first.get("bulkOperationId").asText());
    }

    @Test
    @DisplayName("a bulk row records the same action and the same changes document as a single assign")
    void theTrailReadsTheSameWhicheverDoorWasUsed() throws Exception {
        List<UUID> rows = executionsOf(theRun);

        // One row through the single-row PUT, one through this endpoint, from the same previous
        // state to the same person.
        MvcResult single = mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + ATLAS + "/runs/" + theRun + "/executions/"
                                + rows.get(0) + "/assignee")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":\"" + ANA + "\"}"))
                .andReturn();
        assertThat(single.getResponse().getStatus())
                .as("%s", single.getResponse().getContentAsString())
                .isEqualTo(200);

        commit(assign(List.of(rows.get(1)), ANA, false));

        List<AuditRow> trail = assignmentAudit();
        assertThat(trail).hasSize(2);
        AuditRow throughTheSingleDoor = trail.stream()
                .filter(row -> row.bulkOperationId() == null).findFirst().orElseThrow();
        AuditRow throughTheBulkDoor = trail.stream()
                .filter(row -> row.bulkOperationId() != null).findFirst().orElseThrow();

        assertThat(throughTheBulkDoor.action())
                .as("an 'execution.bulk_assigned' verb would split every 'who took this off Ana' "
                        + "query in two")
                .isEqualTo(throughTheSingleDoor.action());
        assertThat(throughTheBulkDoor.action()).isEqualTo("execution.assigned");
        assertThat(document(throughTheBulkDoor.changes()))
                .as("the two rows differ ONLY in bulk_operation_id")
                .isEqualTo(document(throughTheSingleDoor.changes()));
        assertThat(document(throughTheBulkDoor.changes()))
                .isEqualTo(document("{\"from\":null,\"to\":\"" + ANA + "\"}"));
    }

    // =====================================================================
    // helpers — the request
    // =====================================================================

    private String bulkPath(UUID runId) {
        return "/api/v1/projects/" + ATLAS + "/runs/" + runId + "/executions/bulk";
    }

    private Map<String, Object> assign(List<UUID> executionIds, UUID assigneeId, boolean dryRun) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "ASSIGN");
        body.put("executionIds", ids(executionIds));
        body.put("dryRun", dryRun);
        body.put("payload", Map.of("assigneeId", assigneeId.toString()));
        return body;
    }

    private Map<String, Object> distribute(
            List<UUID> executionIds, List<UUID> assigneeIds, boolean dryRun) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "DISTRIBUTE");
        body.put("executionIds", ids(executionIds));
        body.put("dryRun", dryRun);
        body.put("payload", Map.of("assigneeIds", ids(assigneeIds)));
        return body;
    }

    private static List<String> ids(List<UUID> values) {
        return values.stream().map(UUID::toString).toList();
    }

    private JsonNode commit(Map<String, Object> body) throws Exception {
        MvcResult result = attempt(body);
        assertThat(result.getResponse().getStatus())
                .as("%s", result.getResponse().getContentAsString())
                .isEqualTo(200);
        return body(result);
    }

    private MvcResult attempt(Map<String, Object> body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post(bulkPath(theRun))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    /**
     * The {@code changes} document as a tree, which is the only sound way to compare two of them.
     *
     * <p>~~{@code jsonb} round-trips with its own spacing, so both documents are compared
     * unspaced.~~ → SPACING WAS THE HALF OF IT. {@code jsonb} keeps neither the key order it was
     * given nor the whitespace: it re-emits an object's keys <em>shortest first</em>, so the string
     * {@code ExecutionAssignmentAudit.changes} writes — {@code &#123;"from":…,"to":…&#125;} — comes
     * back as {@code &#123;"to": …, "from": …&#125;}. Stripping spaces left the reordering, so the
     * two database rows compared equal to each other and neither compared equal to the literal.
     *
     * <p>The producer is one hand-built string shared by both doors, so §245's claim was never in
     * doubt; what was wrong was asking a text comparison a question about a document.
     */
    private JsonNode document(String changes) throws Exception {
        return changes == null ? null : json.readTree(changes);
    }

    // =====================================================================
    // helpers — reading the database back
    // =====================================================================

    /** One row of {@code audit_event}, read as the column rather than as a response. */
    private record AuditRow(String action, String changes, UUID bulkOperationId, UUID target) {
    }

    private List<AuditRow> assignmentAudit() throws SQLException {
        List<AuditRow> rows = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                     SELECT action, changes, bulk_operation_id, target_ids[1]
                       FROM audit_event
                      WHERE action LIKE 'execution.%'
                      ORDER BY occurred_at, id
                     """);
             ResultSet result = statement.executeQuery()) {

            while (result.next()) {
                String bulk = result.getString(3);
                rows.add(new AuditRow(
                        result.getString(1),
                        result.getString(2),
                        bulk == null ? null : UUID.fromString(bulk),
                        UUID.fromString(result.getString(4))));
            }
        }
        return rows;
    }

    /** Drops the assignment rows the fixture itself produced, so a count means this act's rows. */
    private void forgetAssignmentAudit() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            execute(connection, "DELETE FROM audit_event WHERE action LIKE 'execution.%'");
        }
    }

    /** The run's executions, in the run's own order — read through the product's own detail. */
    private List<UUID> executionsOf(UUID runId) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + ATLAS + "/runs/" + runId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("%s", result.getResponse().getContentAsString()).isEqualTo(200);

        List<UUID> ids = new ArrayList<>();
        for (JsonNode row : body(result).get("executions")) {
            ids.add(UUID.fromString(row.get("id").asText()));
        }
        return ids;
    }

    private Map<UUID, UUID> assigneesOf(UUID runId) throws SQLException {
        Map<UUID, UUID> byExecution = new LinkedHashMap<>();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT id, assignee_id FROM test_execution WHERE run_id = CAST(? AS uuid)")) {
            statement.setString(1, runId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String assignee = rows.getString(2);
                    byExecution.put(
                            UUID.fromString(rows.getString(1)),
                            assignee == null ? null : UUID.fromString(assignee));
                }
            }
        }
        return byExecution;
    }

    // =====================================================================
    // helpers — the fixture
    // =====================================================================

    private UUID createCase(String title) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("suiteId", PAYMENTS.toString());
        payload.put("objective", "Prove it.");
        payload.put("description", "Seeded by ExecutionBulkAssignTest.");
        payload.put("expectedOutcome", "It works.");
        payload.put("priority", "HIGH");
        payload.put("severity", "HIGH");
        payload.put("automationStatus", "MANUAL");
        payload.put("estimatedDurationSec", 120);
        payload.put("stepFormat", "STRUCTURED");
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", null);
        step.put("keyword", null);
        step.put("action", "Do the thing");
        step.put("testData", null);
        step.put("expectedResult", null);
        payload.put("steps", List.of(step));
        payload.put("changeComment", null);

        return UUID.fromString(created(
                "/api/v1/projects/" + ATLAS + "/cases", payload).get("id").asText());
    }

    private UUID createRun(String name, List<UUID> caseIds) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("planId", null);
        payload.put("configurationId", configurationOf(ATLAS).toString());
        payload.put("releaseVersion", "4.3");
        payload.put("buildNumber", null);
        payload.put("caseIds", ids(caseIds));
        payload.put("assigneeId", null);

        return UUID.fromString(created(
                "/api/v1/projects/" + ATLAS + "/runs", payload).get("id").asText());
    }

    /**
     * Ends the run — by ABORTING it, which is the only ending a run with no results has.
     *
     * <p>{@code NOT_STARTED → COMPLETED} is refused on purpose: <em>"a run with no results is not
     * a pass over its scope, and calling it one puts a 0% completed run into every report"</em>
     * ({@code RunLifecycle}:119). This fixture records nothing, so COMPLETED was never reachable
     * and every arm in the class died in {@code @BeforeEach}. {@code RunLifecycle.isClosed} is
     * {@code COMPLETED || ABORTED}, so an abort freezes the checklist just as hard — which is the
     * property {@link #aClosedRunIsFrozen} is about, and it is now reached the way the product says
     * to reach it.
     */
    private void close(UUID runId) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + ATLAS + "/runs/" + runId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ABORTED\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("the freeze arm needs a genuinely ended run, and ABORTED is the only ending "
                        + "a run with nothing recorded in it has; %s",
                        result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private JsonNode created(String path, Map<String, Object> payload) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .post(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("the fixture depends on the real endpoint at %s; %s",
                        path, result.getResponse().getContentAsString())
                .isEqualTo(201);
        return body(result);
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }

    private void company(UUID id, String slug, String name, Instant now) {
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, slug, name, now));
        }
    }

    private void activeUser(UUID id, String email, String canonicalName, Instant now) {
        if (users.findById(id).isEmpty()) {
            AppUser user = AppUser.invite(id, email, canonicalName, now);
            user.activate("hash", now);
            users.save(user);
        }
    }

    private void seedProject(UUID id, UUID companyId, String key, String name, Instant now) {
        if (projects.findScopedById(id).isEmpty()) {
            projects.save(Project.create(id, companyId, key, name, null, now));
        }
    }

    private void member(UUID companyId, UUID userId, String displayName, Instant now) {
        if (memberships.findByCompanyIdAndUserId(companyId, userId).isEmpty()) {
            CompanyMembership membership =
                    CompanyMembership.invite(companyId, userId, displayName, null, now);
            membership.accept(now);
            memberships.save(membership);
        }
    }

    private void invited(UUID companyId, UUID userId, String displayName, Instant now) {
        if (memberships.findByCompanyIdAndUserId(companyId, userId).isEmpty()) {
            memberships.save(CompanyMembership.invite(companyId, userId, displayName, null, now));
        }
    }

    private void grant(
            String marker, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {
        UUID id = UUID.nameUUIDFromBytes(("execution-bulk-assign:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private void seedCompanyDefaults(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.execute();
        }
    }

    private void clearMutableRows() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            execute(connection, "DELETE FROM audit_event");
            execute(connection, "DELETE FROM defect_link");
            execute(connection, "DELETE FROM evidence");
            execute(connection, "DELETE FROM step_result");
            execute(connection, "DELETE FROM test_execution");
            execute(connection, "DELETE FROM test_run");
            execute(connection, "DELETE FROM plan_scope_item");
            execute(connection, "DELETE FROM test_plan_version");
            execute(connection, "DELETE FROM test_plan");
            execute(connection, "DELETE FROM test_case_version");
            execute(connection, "DELETE FROM test_step");
            execute(connection, "DELETE FROM test_case");
            execute(connection, "DELETE FROM suite_version");
            execute(connection, "DELETE FROM suite");
            execute(connection, "DELETE FROM configuration WHERE NOT is_default");
            execute(connection, "UPDATE key_sequence SET next_value = 1");
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private void seedTree() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            try (var statement = connection.prepareStatement(
                    "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
                statement.setString(1, ACME.toString());
                statement.setString(2, ATLAS.toString());
                statement.execute();
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO suite
                        (id, company_id, project_id, key, key_seq, name, position, lifecycle_status)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'TS-1', 1,
                            'Payments', 0, 'APPROVED')
                    ON CONFLICT (id) DO NOTHING
                    """)) {
                statement.setString(1, PAYMENTS.toString());
                statement.setString(2, ACME.toString());
                statement.setString(3, ATLAS.toString());
                statement.executeUpdate();
            }
        }
    }

    private UUID configurationOf(UUID projectId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT id FROM configuration WHERE project_id = CAST(? AS uuid) "
                             + "AND is_default")) {
            statement.setString(1, projectId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("tcms_seed_project_defaults must seed a default configuration").isTrue();
                return UUID.fromString(rows.getString(1));
            }
        }
    }
}
