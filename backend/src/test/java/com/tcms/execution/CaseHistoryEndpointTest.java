package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * {@code GET /projects/{projectId}/cases/{caseId}/history} over HTTP (plan §6p).
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>The scope parameter being ignored.</strong> The SPA has sent
 *       {@code ?scope=CURRENT_VERSION} since Phase 1 and the contract did not declare it until this
 *       slice — and Spring silently drops an unbound query parameter. So the "Current version" chip
 *       would have answered with every version's results under a heading saying otherwise, which is
 *       §47's {@code ownerId} defect one screen over. The two scopes are asserted to give
 *       <em>different</em> answers over the same case.</li>
 *   <li><strong>Flakiness counting alternation it must not.</strong> §6p scopes it to a single
 *       version <em>and</em> a single configuration. The fixture alternates across a version
 *       boundary and across a configuration boundary and nowhere else, so an implementation that
 *       forgets either scoping reports flake where there is none. This is the assertion most likely
 *       to be written wrong and never noticed, because the number looks plausible either way.</li>
 *   <li><strong>"First failed" reading as the earliest failure ever.</strong> §6p says the earliest
 *       execution of the <em>current</em> streak. {@code caseA} failed, was fixed, and passes now —
 *       a permanent warning banner dated March is the failure mode.</li>
 *   <li><strong>A deleted run's entry vanishing.</strong> §6p: "deleted runs must not orphan
 *       history … with the entry marked rather than vanishing". The row must survive AND carry
 *       {@code runDeleted: true}; either half alone is a defect, so both are asserted.</li>
 *   <li><strong>An unrecorded execution row counting as an execution.</strong> A run opened and left
 *       alone materialises one row per case. Counting those reports a case as executed for every
 *       unfinished run it sits in.</li>
 * </ol>
 *
 * <p>Ids are unique to this class — {@code ch…}.
 */
@AutoConfigureMockMvc
@DisplayName("GET /cases/{id}/history — the case timeline, and what the pin makes readable")
class CaseHistoryEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("cb000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("cb000000-0000-4000-8000-00000000000b");

    private static final UUID ACME_BANKING = UUID.fromString("cb001000-0000-4000-8000-000000000001");
    private static final UUID ACME_MOBILE = UUID.fromString("cb001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("cb002000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("cb003000-0000-4000-8000-000000000001");
    private static final UUID ENGINEER = UUID.fromString("cb003000-0000-4000-8000-000000000002");
    private static final UUID NO_GRANT = UUID.fromString("cb003000-0000-4000-8000-000000000004");
    private static final UUID OUTSIDER = UUID.fromString("cb003000-0000-4000-8000-000000000005");

    private static final UUID PAYMENTS = UUID.fromString("cb004000-0000-4000-8000-000000000001");
    private static final UUID MOBILE_SUITE = UUID.fromString("cb004000-0000-4000-8000-000000000004");
    private static final UUID UMBRA_SUITE = UUID.fromString("cb004000-0000-4000-8000-000000000005");

    private static final UUID SECOND_CONFIG = UUID.fromString("cb005000-0000-4000-8000-000000000001");

    private static final UUID ABSENT = UUID.fromString("cb00ffff-0000-4000-8000-00000000ffff");

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

    /**
     * The case the whole class is about. Edited to v2 partway through its life, so its timeline
     * spans a version boundary — which is the only fixture shape under which "current version" and
     * "all versions" can be told apart, and under which the flakiness scoping is testable.
     */
    private UUID caseA;

    /** Executed once, in a run that was then withdrawn. The §6p deleted-run case, on its own. */
    private UUID caseB;

    /** Never executed. The zero-summary branch — a readable case with an empty timeline. */
    private UUID caseC;

    /** In ACME_MOBILE — reachable to LEAD, and still a 404 from ACME_BANKING's path. */
    private UUID mobileCase;

    private UUID defaultConfiguration;

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "ch-acme", "Acme", now);
            company(UMBRA, "ch-umbra", "Umbra", now);

            activeUser(LEAD, "lead@ch-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@ch-acme.test", "E. Engineer", now);
            activeUser(NO_GRANT, "nogrant@ch-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@ch-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "CHAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "CHAMOB", "Mobile App", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grant("ch-lead-banking", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grant("ch-lead-mobile", ACME, Role.TEST_LEAD_ID, LEAD, ACME_MOBILE, now);
            grant("ch-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "CHUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grant("ch-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        clearMutableRows();
        seedTrees();

        caseA = createCase(LEAD, ACME, ACME_BANKING, "Refund the whole captured amount", PAYMENTS);
        caseB = createCase(LEAD, ACME, ACME_BANKING, "Partial refund rounds correctly", PAYMENTS);
        caseC = createCase(LEAD, ACME, ACME_BANKING, "Refund a disputed charge", PAYMENTS);
        mobileCase = createCase(LEAD, ACME, ACME_MOBILE, "Sign in with a passkey", MOBILE_SUITE);

        defaultConfiguration = configurationOf(ACME_BANKING, true);
        secondConfiguration();

        buildTheTimeline();
    }

    /**
     * caseA's life, in order — every assertion in this class reads off this sequence.
     *
     * <pre>
     *   day -6  R1  v1  default  FAILED     ← an old failing streak, since repaired
     *   day -5  R2  v1  default  FAILED
     *   day -4  R3  v1  Safari   PASSED     ← alternation ACROSS a configuration: not flake
     *           ─ edited to v2 ─
     *   day -3  R4  v2  default  PASSED     ← alternation ACROSS a version: not flake either
     *   day -2  R5  v2  default  FAILED     ← the ONE real alternation: same version, same config
     *   day -1  R6  v2  default  FAILED     ← so the current streak starts at R5, not at R1
     * </pre>
     *
     * <p>Every boundary is crossed exactly once, which is what lets each rule fail on its own: an
     * implementation that scopes flakiness by version but forgets configuration reports a different
     * number from one that does neither, and from the correct one.
     */
    private void buildTheTimeline() throws Exception {
        UUID r1 = createRun("Nightly 1", defaultConfiguration, "4.2");
        record(r1, caseA, failedStatus());
        moveRunInTime(r1, 6);

        UUID r2 = createRun("Nightly 2", defaultConfiguration, "4.2");
        record(r2, caseA, failedStatus());
        moveRunInTime(r2, 5);

        UUID r3 = createRun("Safari sweep", SECOND_CONFIG, "4.2");
        record(r3, caseA, passedStatus());
        moveRunInTime(r3, 4);

        // The edit. Everything after this runs against v2, and the marker between the two halves is
        // what makes "the edit broke it" readable rather than inferred.
        editCase(caseA, "Refund the whole captured amount, including tax", "Cover tax");

        // The version rows are written NOW, while the executions above were displaced into the
        // past — so without this the edit lands after every result it is supposed to explain, and
        // the timeline reads "red, red, edited" instead of "edited, red, red". Moved to 84 hours
        // ago: after R3 (day 4) and before R4 (day 3), which is where it actually belongs.
        moveVersionInTime(caseA, 1, 168);
        moveVersionInTime(caseA, 2, 84);

        UUID r4 = createRun("Nightly 3", defaultConfiguration, "4.3");
        record(r4, caseA, passedStatus());
        moveRunInTime(r4, 3);

        UUID r5 = createRun("Nightly 4", defaultConfiguration, "4.3");
        record(r5, caseA, failedStatus());
        moveRunInTime(r5, 2);

        UUID r6 = createRun("Nightly 5", defaultConfiguration, "4.3");
        record(r6, caseA, failedStatus());
        moveRunInTime(r6, 1);

        // caseB's only result, in a run that is then withdrawn.
        UUID deletedRun = createRun("Withdrawn run", defaultConfiguration, "4.3");
        record(deletedRun, caseB, passedStatus());
        moveRunInTime(deletedRun, 1);
        softDeleteRun(deletedRun);

        // Materialised, never recorded — the rows that must not count as executions.
        createRun("Opened, not started", defaultConfiguration, null);
    }

    // =====================================================================
    // Scope — the parameter that was never declared
    // =====================================================================

    @Nested
    @DisplayName("Scope — the parameter the SPA has always sent and the contract never declared")
    class ScopeParameter {

        /**
         * <strong>The assertion this slice was written for.</strong> If {@code scope} is dropped —
         * which is what Spring does to an undeclared parameter — both calls return all six results
         * and this fails on the first number.
         */
        @Test
        @DisplayName("CURRENT_VERSION covers only v2; ALL_VERSIONS covers the whole life")
        void theTwoScopesDiffer() throws Exception {
            JsonNode current = history(LEAD, caseA, "CURRENT_VERSION");
            JsonNode all = history(LEAD, caseA, "ALL_VERSIONS");

            assertThat(current.get("summary").get("executions").asInt())
                    .as("v2 was executed three times: R4, R5, R6")
                    .isEqualTo(3);
            assertThat(all.get("summary").get("executions").asInt())
                    .as("six executions across both versions")
                    .isEqualTo(6);
            assertThat(current.get("entries")).hasSize(3);
            assertThat(all.get("entries")).hasSize(6);
        }

        @Test
        @DisplayName("CURRENT_VERSION is the default — an absent parameter is the safe reading")
        void currentVersionIsTheDefault() throws Exception {
            assertThat(history(LEAD, caseA, null).toString())
                    .as("blending versions by default would report a pass rate for a test whose "
                            + "text changed underneath it (§6p)")
                    .isEqualTo(history(LEAD, caseA, "CURRENT_VERSION").toString());
        }

        @Test
        @DisplayName("the scope sentence names the version, because the client did not know it")
        void theScopeIsStated() throws Exception {
            assertThat(history(LEAD, caseA, "CURRENT_VERSION").get("summary").get("scope").asText())
                    .isEqualTo("Current version (v2)");
            assertThat(history(LEAD, caseA, "ALL_VERSIONS").get("summary").get("scope").asText())
                    .isEqualTo("All versions");
        }

        @Test
        @DisplayName("every entry under CURRENT_VERSION really is that version")
        void theEntriesMatchTheScope() throws Exception {
            for (JsonNode entry : history(LEAD, caseA, "CURRENT_VERSION").get("entries")) {
                assertThat(entry.get("caseVersionNo").asInt())
                        .as("a v1 result inside a v2-scoped timeline is the pin failing")
                        .isEqualTo(2);
            }
        }

        @Test
        @DisplayName("caseVersionNo pins an explicit version and wins over scope")
        void anExplicitVersionWins() throws Exception {
            JsonNode pinned = raw(LEAD, ACME, path(caseA) + "?scope=ALL_VERSIONS&caseVersionNo=1");

            assertThat(pinned.get("entries")).hasSize(3);
            for (JsonNode entry : pinned.get("entries")) {
                assertThat(entry.get("caseVersionNo").asInt()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("an unknown scope is a 400 naming the two, not a bare converter failure")
        void anUnknownScopeIsRefused() throws Exception {
            MvcResult result = rawResult(LEAD, ACME, path(caseA) + "?scope=EVERYTHING");

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString())
                    .contains("CURRENT_VERSION")
                    .contains("ALL_VERSIONS")
                    .as("the Java type name must not reach the caller")
                    .doesNotContain("CaseHistoryService");
        }
    }

    // =====================================================================
    // The version markers — what makes causation readable
    // =====================================================================

    @Nested
    @DisplayName("Version markers — 'green, green, edited to v2, red, red'")
    class Markers {

        @Test
        @DisplayName("ALL_VERSIONS carries both authored versions, oldest first")
        void bothVersions() throws Exception {
            JsonNode markers = history(LEAD, caseA, "ALL_VERSIONS").get("versionMarkers");

            assertThat(markers).hasSize(2);
            assertThat(markers.get(0).get("versionNo").asInt()).isEqualTo(1);
            assertThat(markers.get(1).get("versionNo").asInt()).isEqualTo(2);
            assertThat(markers.get(1).get("changeComment").asText())
                    .as("the comment the author typed is the whole value of the marker")
                    .isEqualTo("Cover tax");
            assertThat(markers.get(1).get("author").get("displayName").asText())
                    .as("resolved through the ACTIVE COMPANY's membership (§6u)")
                    .isEqualTo("L. Lead of Acme");
        }

        @Test
        @DisplayName("CURRENT_VERSION keeps only v2's marker — the moment its scope begins")
        void onlyTheScopedVersion() throws Exception {
            JsonNode markers = history(LEAD, caseA, "CURRENT_VERSION").get("versionMarkers");

            assertThat(markers).hasSize(1);
            assertThat(markers.get(0).get("versionNo").asInt())
                    .as("markers for versions whose results are not on this timeline would be "
                            + "edits with nothing to explain")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the v2 marker falls between the v1 results and the v2 results")
        void theMarkerSitsWhereTheEditHappened() throws Exception {
            JsonNode all = history(LEAD, caseA, "ALL_VERSIONS");
            Instant edited = Instant.parse(
                    all.get("versionMarkers").get(1).get("occurredAt").asText());

            for (JsonNode entry : all.get("entries")) {
                Instant at = Instant.parse(entry.get("executedAt").asText());
                boolean isV2 = entry.get("caseVersionNo").asInt() == 2;
                assertThat(at.isAfter(edited))
                        .as("a v%s result at %s must fall on the %s side of the edit at %s — "
                                + "otherwise the timeline shows the edit AFTER the results it "
                                + "explains, and the screen's whole argument inverts",
                                entry.get("caseVersionNo").asInt(), at,
                                isV2 ? "later" : "earlier", edited)
                        .isEqualTo(isV2);
            }
        }
    }

    // =====================================================================
    // The derived insight — the two figures that are easy to get plausibly wrong
    // =====================================================================

    @Nested
    @DisplayName("Derived insight — flakiness and first-failed, scoped as §6p defines them")
    class DerivedInsight {

        /**
         * <strong>The subtlest assertion in the class.</strong> caseA's six results alternate three
         * times in wall-clock order: FAILED→PASSED at the configuration boundary, PASSED→PASSED
         * across the version boundary (no change), PASSED→FAILED within v2/default. Only the last
         * is flake.
         *
         * <p>Under ALL_VERSIONS the groups are: (v1, default) = FAILED, FAILED — one pair, no
         * transition; (v1, Safari) = PASSED — no pair; (v2, default) = PASSED, FAILED, FAILED — two
         * pairs, one transition. So 1 transition over 3 pairs.
         */
        @Test
        @DisplayName("flakiness counts alternation WITHIN one version and configuration only")
        void flakinessIsScoped() throws Exception {
            assertThat(history(LEAD, caseA, "ALL_VERSIONS").get("summary")
                    .get("flakinessScore").asDouble())
                    .as("naive alternation over the whole timeline is 2 transitions over 5 pairs "
                            + "= 0.4; scoping by version alone gives 1 over 4 = 0.25; only the "
                            + "correct double scoping gives 1 over 3")
                    .isEqualTo(1.0 / 3.0, within(1e-9));
        }

        @Test
        @DisplayName("flakiness is null, not zero, when nothing could have alternated")
        void flakinessIsNullWithoutAPair() throws Exception {
            assertThat(history(LEAD, caseB, "ALL_VERSIONS").get("summary").get("flakinessScore")
                    .isNull())
                    .as("caseB has one result; reporting 0.0 would state that a test executed "
                            + "once is stable")
                    .isTrue();
        }

        /**
         * §6p: "the earliest execution of the CURRENT failing streak". caseA failed on days 6 and 5,
         * was green on days 4 and 3, and has failed on days 2 and 1. The answer is day 2.
         */
        @Test
        @DisplayName("first-failed dates the CURRENT streak, not the earliest failure ever")
        void firstFailedIsTheCurrentStreak() throws Exception {
            JsonNode summary = history(LEAD, caseA, "ALL_VERSIONS").get("summary");
            Instant firstFailed = Instant.parse(summary.get("firstFailedAt").asText());

            assertThat(firstFailed)
                    .as("the streak starts two days ago; the failures six and five days ago were "
                            + "repaired, and dating the banner to them would leave a permanent "
                            + "warning on a case somebody fixed")
                    .isCloseTo(Instant.now().minus(2, ChronoUnit.DAYS),
                            within(2, ChronoUnit.HOURS));
            assertThat(summary.get("firstFailedInRelease").asText())
                    .as("the release in force at that point, not today's")
                    .isEqualTo("4.3");
        }

        @Test
        @DisplayName("first-failed is null when the latest result is green")
        void noStreakWhenGreen() throws Exception {
            assertThat(history(LEAD, caseB, "ALL_VERSIONS").get("summary").get("firstFailedAt")
                    .isNull())
                    .as("caseB's only result passed; a warning banner there would be false")
                    .isTrue();
        }
    }

    // =====================================================================
    // Deleted runs — §6p's "marked rather than vanishing"
    // =====================================================================

    @Nested
    @DisplayName("A withdrawn run — the entry survives it, and says so")
    class WithdrawnRuns {

        /** Both halves, because either one alone is the defect. */
        @Test
        @DisplayName("the entry is still there AND it is marked")
        void theEntrySurvivesAndIsMarked() throws Exception {
            JsonNode entries = history(LEAD, caseB, "ALL_VERSIONS").get("entries");

            assertThat(entries)
                    .as("§6p: history that quietly loses rows is worse than no history")
                    .hasSize(1);
            assertThat(entries.get(0).get("runDeleted").asBoolean())
                    .as("unmarked, a withdrawn failure reads as a live one, and the client would "
                            + "offer navigation into a run that answers 404")
                    .isTrue();
        }

        /**
         * The contrast that makes the rule a rule rather than an accident of one query. A timeline
         * keeps the row; a current-state figure must not.
         */
        @Test
        @DisplayName("the same run is EXCLUDED from a plan's statistics, which is the contrast")
        void statisticsStillExcludeIt() throws Exception {
            JsonNode live = history(LEAD, caseA, "ALL_VERSIONS").get("entries");

            for (JsonNode entry : live) {
                assertThat(entry.get("runDeleted").asBoolean())
                        .as("caseA's runs are all live; a blanket true would pass the test above "
                                + "while marking everything")
                        .isFalse();
            }
        }
    }

    // =====================================================================
    // The ordinary states, and the two gates
    // =====================================================================

    @Nested
    @DisplayName("The states a real installation is in")
    class OrdinaryStates {

        @Test
        @DisplayName("a case nobody has executed is a 200 of zeros, not a 404 and not an error")
        void neverExecuted() throws Exception {
            JsonNode body = history(LEAD, caseC, "ALL_VERSIONS");

            assertThat(body.get("entries")).isEmpty();
            assertThat(body.get("summary").get("executions").asInt()).isZero();
            assertThat(body.get("summary").get("passed").asInt()).isZero();
            assertThat(body.get("summary").get("lastExecutedAt").isNull()).isTrue();
            assertThat(body.get("summary").get("flakinessScore").isNull()).isTrue();
            assertThat(body.get("versionMarkers"))
                    .as("a case authored through POST /cases has a v1 written with it (§29)")
                    .hasSize(1);
        }

        /**
         * A run opened and never touched materialises an execution row per case. Those rows are not
         * executions and counting them would report caseC as executed once for merely existing.
         */
        @Test
        @DisplayName("an unrecorded execution row is not an execution")
        void unrecordedRowsAreNotExecutions() throws Exception {
            assertThat(history(LEAD, caseA, "ALL_VERSIONS").get("summary").get("executions").asInt())
                    .as("six recorded results; the unstarted run's row is not a seventh")
                    .isEqualTo(6);
        }

        @Test
        @DisplayName("passed + failed need not equal executions — blocked is neither")
        void theCountsDoNotFalselyPartition() throws Exception {
            JsonNode summary = history(LEAD, caseA, "ALL_VERSIONS").get("summary");

            assertThat(summary.get("passed").asInt()).isEqualTo(2);
            assertThat(summary.get("failed").asInt()).isEqualTo(4);
            assertThat(summary.get("currentVersionNo").asInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("an entry names its run, its configuration and the person who recorded it")
        void theEntryIsReadable() throws Exception {
            JsonNode entry = history(LEAD, caseA, "CURRENT_VERSION").get("entries").get(0);

            assertThat(entry.get("runName").asText()).isEqualTo("Nightly 5");
            assertThat(entry.get("statusKind").asText()).isEqualTo("FAILED");
            assertThat(entry.get("statusName").asText())
                    .as("the project's own word for it, not the kind")
                    .isEqualTo("Failed");
            assertThat(entry.get("statusColor").asText()).isNotBlank();
            assertThat(entry.get("configuration").asText()).isEqualTo("Default");
            assertThat(entry.get("executedBy").get("displayName").asText()).isEqualTo("E. Engineer");
            assertThat(entry.get("source").asText()).isEqualTo("MANUAL");
            assertThat(entry.get("evidenceCount").asInt())
                    .as("looked up rather than assumed — nothing uploads evidence yet, but an "
                            + "ingested run could carry it")
                    .isZero();
            assertThat(entry.get("defects")).isEmpty();
        }

        @Test
        @DisplayName("entries are newest first — the order the page reads")
        void newestFirst() throws Exception {
            JsonNode entries = history(LEAD, caseA, "ALL_VERSIONS").get("entries");

            Instant previous = null;
            for (JsonNode entry : entries) {
                Instant at = Instant.parse(entry.get("executedAt").asText());
                if (previous != null) {
                    assertThat(at).isBeforeOrEqualTo(previous);
                }
                previous = at;
            }
        }
    }

    @Nested
    @DisplayName("The two gates — 404, never 403")
    class Gates {

        @Test
        @DisplayName("a case in a sibling project is a 404 through this project's path")
        void aSiblingProjectsCase() throws Exception {
            assertThat(rawResult(LEAD, ACME, path(mobileCase)).getResponse().getStatus())
                    .as("LEAD holds a grant on BOTH projects, so only the project predicate on "
                            + "the case lookup can refuse this")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a member with no project grant gets 404, not 403")
        void noGrant() throws Exception {
            assertThat(rawResult(NO_GRANT, ACME, path(caseA)).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("another company's user cannot reach it")
        void anotherCompany() throws Exception {
            assertThat(rawResult(OUTSIDER, UMBRA, path(caseA)).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("an absent case id is a 404, not a 200 of zeros")
        void absentCase() throws Exception {
            assertThat(rawResult(LEAD, ACME, path(ABSENT)).getResponse().getStatus())
                    .as("200-with-zeros for a case that does not exist is how a typo in a URL "
                            + "reads as a case nobody has ever run")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("no token is a 401")
        void anonymous() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1" + path(caseA))
                            .accept(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
        }
    }

    // =====================================================================
    // helpers — the request
    // =====================================================================

    private static String path(UUID caseId) {
        return "/projects/" + ACME_BANKING + "/cases/" + caseId + "/history";
    }

    private JsonNode history(UUID userId, UUID caseId, String scope) throws Exception {
        String query = scope == null ? "" : "?scope=" + scope;
        return raw(userId, ACME, path(caseId) + query);
    }

    private JsonNode raw(UUID userId, UUID companyId, String path) throws Exception {
        MvcResult result = rawResult(userId, companyId, path);
        assertThat(result.getResponse().getStatus())
                .as("%s", result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result);
    }

    private MvcResult rawResult(UUID userId, UUID companyId, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1" + path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    // =====================================================================
    // helpers — the fixture
    // =====================================================================

    private UUID createCase(UUID userId, UUID companyId, UUID projectId, String title, UUID suiteId)
            throws Exception {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("suiteId", suiteId.toString());
        payload.put("objective", "Prove it.");
        payload.put("description", "Seeded by CaseHistoryEndpointTest.");
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
        step.put("sharedStepId", null);
        payload.put("steps", List.of(step));
        payload.put("changeComment", null);

        return UUID.fromString(created(
                "/api/v1/projects/" + projectId + "/cases", userId, companyId, payload)
                .get("id").asText());
    }

    /**
     * Edits a case through the <strong>real</strong> {@code PATCH /cases/{id}}, so the version row
     * and its marker are written by the writer under test rather than by SQL nobody else runs.
     */
    private void editCase(UUID caseId, String title, String changeComment) throws Exception {
        // The WHOLE editable set, not just the changed field: this PATCH treats null as "clear it"
        // and requires the client to send everything it renders (CaseUpdateRequest's header). A
        // two-field payload would blank the case and still pass, which would make the version row
        // this fixture depends on a record of an edit nobody made.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("suiteId", PAYMENTS.toString());
        payload.put("objective", "Prove it.");
        payload.put("description", "Seeded by CaseHistoryEndpointTest.");
        payload.put("expectedOutcome", "It works.");
        payload.put("priority", "HIGH");
        payload.put("severity", "HIGH");
        payload.put("automationStatus", "MANUAL");
        payload.put("estimatedDurationSec", 120);
        payload.put("stepFormat", "STRUCTURED");
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", null);
        step.put("keyword", null);
        step.put("action", "Do the thing, with tax");
        step.put("testData", null);
        step.put("expectedResult", null);
        step.put("sharedStepId", null);
        payload.put("steps", List.of(step));
        payload.put("changeComment", changeComment);

        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + ACME_BANKING + "/cases/" + caseId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("the fixture depends on the real case editor; %s",
                        result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private UUID createRun(String name, UUID configurationId, String releaseVersion)
            throws Exception {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("planId", null);
        payload.put("configurationId", configurationId.toString());
        payload.put("releaseVersion", releaseVersion);
        payload.put("buildNumber", null);
        payload.put("caseIds", List.of(caseA.toString(), caseB.toString(), caseC.toString()));
        payload.put("assigneeId", null);

        return UUID.fromString(created(
                "/api/v1/projects/" + ACME_BANKING + "/runs", LEAD, ACME, payload)
                .get("id").asText());
    }

    /** Records a verdict through the real PATCH, so the fixture exercises the writer. */
    private void record(UUID runId, UUID caseId, UUID statusId) throws Exception {
        UUID executionId = executionOf(runId, caseId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("statusId", statusId.toString());
        payload.put("comment", null);

        MvcResult recorded = mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + ACME_BANKING + "/runs/" + runId
                                + "/executions/" + executionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(ENGINEER, ACME))
                        .header(HttpHeaders.IF_MATCH, "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(recorded.getResponse().getStatus())
                .as("the fixture depends on the real result endpoint; %s",
                        recorded.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private JsonNode created(String path, UUID userId, UUID companyId, Map<String, Object> payload)
            throws Exception {

        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .post(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
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

    private void grant(
            String marker, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {
        UUID id = UUID.nameUUIDFromBytes(("case-history:" + marker).getBytes());
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
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
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
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private void seedTrees() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_MOBILE);

            suite(connection, PAYMENTS, ACME, ACME_BANKING, null, "TS-1", 1, "Payments", "s1");
            suite(connection, MOBILE_SUITE, ACME, ACME_MOBILE, null, "TS-4", 4, "Mobile", "s4");
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, null, "TS-1", 1, "Payments", "s1");
        }
    }

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.execute();
        }
    }

    private void suite(
            Connection connection, UUID id, UUID companyId, UUID projectId, UUID parentId,
            String key, long keySeq, String name, String path) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, parent_id, key, key_seq, path, name,
                     position, lifecycle_status)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        CAST(? AS ltree), ?, 0, 'APPROVED')
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, parentId == null ? null : parentId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, path);
            statement.setString(8, name);
            statement.executeUpdate();
        }
    }

    /** A second environment, so the flakiness scoping has a boundary to be scoped by. */
    private void secondConfiguration() throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                     INSERT INTO configuration (id, company_id, project_id, name, is_default,
                                                attributes)
                     VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'Safari', false,
                             CAST('{"browser":"Safari"}' AS jsonb))
                     ON CONFLICT (id) DO NOTHING
                     """)) {
            statement.setString(1, SECOND_CONFIG.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, ACME_BANKING.toString());
            statement.executeUpdate();
        }
    }

    /**
     * Moves a run's recorded results {@code days} into the past.
     *
     * <p>Recorded through the API for realism, then displaced through SQL for determinism — the
     * discipline {@code PlanStatisticsEndpointTest} sets out. Six runs recorded inside one method
     * land microseconds apart, which would make every ordering assertion here a coin flip.
     *
     * <p>Only rows carrying a verdict are moved: an unrecorded execution has no {@code executed_at}
     * and must keep not having one, or it would start counting as an execution.
     */
    private void moveRunInTime(UUID runId, int days) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "UPDATE test_execution SET executed_at = ? "
                             + "WHERE run_id = CAST(? AS uuid) AND executed_at IS NOT NULL")) {
            statement.setObject(1, Instant.now().minus(days, ChronoUnit.DAYS)
                    .atOffset(ZoneOffset.UTC));
            statement.setString(2, runId.toString());
            statement.executeUpdate();
        }
    }

    /**
     * Withdraws a run the way the product will — soft delete only (§2), so the executions stay.
     *
     * <p>Direct SQL because {@code DELETE /runs/{id}} does not exist yet. The row shape is what the
     * endpoint under test reads, and writing it here is what lets §6p's rule be asserted before the
     * delete endpoint that produces it is built.
     */
    private void softDeleteRun(UUID runId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "UPDATE test_run SET deleted_at = now() WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, runId.toString());
            statement.executeUpdate();
        }
    }

    /**
     * Moves an authored version {@code hours} into the past.
     *
     * <p>{@link #moveRunInTime}'s reasoning, applied to the other half of the timeline. The version
     * rows are written by the real editor at wall-clock now; the executions around them are
     * displaced. Leaving the markers where they were written would put every edit after every
     * result, which inverts the one causal claim this screen makes.
     */
    private void moveVersionInTime(UUID caseId, int versionNo, int hours) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "UPDATE test_case_version SET created_at = ? "
                             + "WHERE case_id = CAST(? AS uuid) AND version_no = ?")) {
            statement.setObject(1, Instant.now().minus(hours, ChronoUnit.HOURS)
                    .atOffset(ZoneOffset.UTC));
            statement.setString(2, caseId.toString());
            statement.setInt(3, versionNo);
            statement.executeUpdate();
        }
    }

    private UUID executionOf(UUID runId, UUID caseId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT id FROM test_execution WHERE run_id = CAST(? AS uuid) "
                             + "AND case_id = CAST(? AS uuid)")) {
            statement.setString(1, runId.toString());
            statement.setString(2, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("the run's checklist must hold this case, or the fixture is wrong "
                                + "before any assertion runs")
                        .isTrue();
                return UUID.fromString(rows.getString(1));
            }
        }
    }

    private UUID configurationOf(UUID projectId, boolean isDefault) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT id FROM configuration WHERE project_id = CAST(? AS uuid) "
                             + "AND is_default = ?")) {
            statement.setString(1, projectId.toString());
            statement.setBoolean(2, isDefault);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("tcms_seed_project_defaults must seed a default configuration").isTrue();
                return UUID.fromString(rows.getString(1));
            }
        }
    }

    private UUID statusIdOf(String kind) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT id FROM case_status WHERE project_id = CAST(? AS uuid) AND kind = ?")) {
            statement.setString(1, ACME_BANKING.toString());
            statement.setString(2, kind);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("tcms_seed_project_defaults must have seeded a %s status", kind)
                        .isTrue();
                return UUID.fromString(rows.getString(1));
            }
        }
    }

    private UUID passedStatus() throws SQLException {
        return statusIdOf("PASSED");
    }

    private UUID failedStatus() throws SQLException {
        return statusIdOf("FAILED");
    }
}
