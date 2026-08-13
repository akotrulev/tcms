package com.tcms.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.contract.OpenApiContract;
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
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /projects/{projectId}/dashboard} — the project's landing page.
 *
 * <h2>The failures this class exists to catch, none of which throw</h2>
 *
 * <ol>
 *   <li><strong>A tile whose count and whose link disagree.</strong> The whole design of §6o is that
 *       a number is the entrance to the list behind it. Every count assertion here is made
 *       <em>against the case list called with the tile's own filter</em> rather than against a
 *       literal, because a literal agreeing with a wrong implementation is how both screens end up
 *       wrong together (the §46 lesson, and §32's before it).</li>
 *   <li><strong>A filter key the case list does not bind.</strong> The by-owner segments carried
 *       {@code ownerId} while {@code GET /cases} had no such parameter, so the link silently opened
 *       <em>everyone's</em> cases in that status under a heading naming one person. Nothing throws,
 *       the page renders, and the number above the link is right. Caught by driving the link rather
 *       than by reading it.</li>
 *   <li><strong>An empty "Needs attention" panel read as "nothing needs attention".</strong>
 *       {@code case_health} has no writer, so the panel is empty in every deployment — and empty
 *       there is a claim. {@code healthComputedAt} is what distinguishes "clean" from "nobody has
 *       looked", and it must be null while that stays true.</li>
 *   <li><strong>A lifecycle status vanishing at zero.</strong> The tiles are a distribution over a
 *       closed set of four; dropping the empty ones shows three tiles and looks complete. "0
 *       approved" is the most useful number on the page today.</li>
 *   <li><strong>A feature breakdown that double-counts, or that mixes vocabularies.</strong> Feature
 *       is MULTI (§6d) so a case in two features belongs in two bars — but one join table holds
 *       every dimension, so an unbounded query also bars Test Type and Test Level.</li>
 *   <li><strong>Automation coverage denominated against everything.</strong> With
 *       {@code NOT_AUTOMATABLE} in the divisor, marking a case impossible improves the number.</li>
 *   <li><strong>Every creation counted as a modification.</strong> Since §29 a created case is
 *       written with its v1 in the same transaction, so counting all version rows makes the two
 *       activity series identical — and it looks like data.</li>
 * </ol>
 *
 * <h2>The fixture</h2>
 *
 * <p>Two companies with identically keyed cases, so only ids prove the scoping. A soft-deleted case
 * and a live case inside a <em>soft-deleted suite</em> — both must be absent from every count here
 * exactly as they are from the list, which is the property the shared predicate buys. Two owners
 * plus an unowned case, cases in one and two features, one of every automation status including a
 * {@code NOT_AUTOMATABLE} one, and cases created and edited on known days inside and outside the
 * thirty-day window.
 *
 * <p>Ids are unique to this class — {@code da…}. Sharing them with another test class is how §19's
 * first defect happened: green alone, red in the full suite.
 */
@AutoConfigureMockMvc
@DisplayName("Dashboard endpoint — every count is a link, and the links have to work")
class DashboardEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("da000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("da000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD, and holding every case the assertions name. */
    private static final UUID ACME_BANKING = UUID.fromString("da001000-0000-4000-8000-000000000001");
    /** Same company, NOT granted to LEAD — its cases must never reach BANKING's dashboard. */
    private static final UUID ACME_MOBILE = UUID.fromString("da001000-0000-4000-8000-000000000002");
    /** Freshly provisioned: no suites at all. The page must still render. */
    private static final UUID ACME_EMPTY = UUID.fromString("da001000-0000-4000-8000-000000000003");
    private static final UUID UMBRA_BANKING = UUID.fromString("da002000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("da003000-0000-4000-8000-000000000001");
    /** ACTIVE member of Acme holding nothing at all. Persona 3. */
    private static final UUID NO_GRANT = UUID.fromString("da003000-0000-4000-8000-000000000002");
    /** Company Admin — reaches ACME_EMPTY through a COMPANY-scope grant. */
    private static final UUID ADMIN = UUID.fromString("da003000-0000-4000-8000-000000000003");
    /** A second owner, so the by-owner panel has more than one bar to order. */
    private static final UUID ANA = UUID.fromString("da003000-0000-4000-8000-000000000004");

    private static final UUID PAYMENTS = UUID.fromString("da004000-0000-4000-8000-000000000001");
    private static final UUID CHECKOUT = UUID.fromString("da004000-0000-4000-8000-000000000002");
    /** Soft-deleted, holding a LIVE case that must be absent from every number on the page. */
    private static final UUID RETIRED_SUITE = UUID.fromString("da004000-0000-4000-8000-000000000003");
    private static final UUID MOBILE_SUITE = UUID.fromString("da004000-0000-4000-8000-000000000004");
    private static final UUID UMBRA_SUITE = UUID.fromString("da004000-0000-4000-8000-000000000005");

    private static final UUID F_PAYMENTS = UUID.fromString("da005000-0000-4000-8000-000000000001");
    private static final UUID F_REFUNDS = UUID.fromString("da005000-0000-4000-8000-000000000002");

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

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "dash-acme", "Acme", now);
            company(UMBRA, "dash-umbra", "Umbra", now);

            activeUser(LEAD, "lead@dash-acme.test", "Leonora Lead", now);
            activeUser(NO_GRANT, "nogrant@dash-acme.test", "N. Grant", now);
            activeUser(ADMIN, "admin@dash-acme.test", "A. Admin", now);
            activeUser(ANA, "ana@dash-acme.test", "Ana Author", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "DAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "DAMOB", "Mobile App", now);
            seedProject(ACME_EMPTY, ACME, "DANEW", "Just Provisioned", now);

            member(ACME, LEAD, "L. Lead (Acme)", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            member(ACME, ADMIN, "A. Admin", now);
            member(ACME, ANA, "Ana Author (Acme)", now);

            grantOnProject("dash-lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnCompany("dash-admin", ACME, Role.COMPANY_ADMIN_ID, ADMIN, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status ->
                seedProject(UMBRA_BANKING, UMBRA, "DUOLB", "Online Banking", now)));

        seedVocabularies();
        seedTrees();
        seedCases();
        seedClassifications();
    }

    // =====================================================================
    // The contract's own shape
    // =====================================================================

    @Nested
    @DisplayName("The response — present, complete, and scoped to one project")
    class Shape {

        @Test
        @DisplayName("every field the contract marks required is present")
        void requiredFieldsArePresent() throws Exception {
            JsonNode body = dashboardAs(LEAD, ACME, ACME_BANKING);

            assertThat(List.of("generatedAt", "lifecycle", "health", "healthComputedAt",
                    "byFeature", "byOwner", "automationCoverage", "recentActivity"))
                    .allSatisfy(field -> assertThat(body.has(field))
                            .as("RepositoryDashboard.%s is required by openapi.yaml", field)
                            .isTrue());
        }

        @Test
        @DisplayName("a sibling project the caller was not granted contributes nothing")
        void siblingProjectsDoNotLeak() throws Exception {
            // ACME_MOBILE is in the same company, so RLS and the Hibernate filter both pass it.
            // Only the project predicate keeps it out — persona 3's failure mode one level up,
            // where the leak is a number rather than a row.
            assertThat(totalOf(dashboardAs(LEAD, ACME, ACME_BANKING)))
                    .as("TC-20 lives in ACME_MOBILE and must not be counted here")
                    .isEqualTo(liveCaseCount());
        }

        @Test
        @DisplayName("a member with no project grant gets 404, not an empty dashboard")
        void noGrantIsNotFound() throws Exception {
            assertThat(statusOf(NO_GRANT, ACME, ACME_BANKING)).isEqualTo(404);
        }

        @Test
        @DisplayName("another client's project is 404 and never 403")
        void crossCompanyIsNotFound() throws Exception {
            // 403 would confirm the id names something real, making the path an oracle for another
            // client's project ids (§6t).
            assertThat(statusOf(LEAD, ACME, UMBRA_BANKING)).isEqualTo(404);
        }

        @Test
        @DisplayName("a project with no suites renders zeros rather than 404 or an empty body")
        void anEmptyProjectStillRenders() throws Exception {
            // The state of EVERY freshly provisioned project, and the page a new client lands on
            // first. The activity series must still carry its thirty days: the SPA draws the axis
            // from the first and last point, so an empty array is a chart with no axis.
            JsonNode body = dashboardAs(ADMIN, ACME, ACME_EMPTY);

            assertThat(statusOf(ADMIN, ACME, ACME_EMPTY)).isEqualTo(200);
            assertThat(body.get("lifecycle")).hasSize(4);
            assertThat(totalOf(body)).isZero();
            assertThat(body.get("byFeature")).isEmpty();
            assertThat(body.get("byOwner")).isEmpty();
            assertThat(body.get("recentActivity")).hasSize(30);
            assertThat(body.get("automationCoverage").get("coveragePercent").asDouble())
                    .as("no automatable case means no coverage figure — 0, never a division by zero")
                    .isZero();
        }
    }

    // =====================================================================
    // The tiles, and the lists behind them
    // =====================================================================

    @Nested
    @DisplayName("Lifecycle tiles — a distribution, including the statuses at zero")
    class Lifecycle {

        @Test
        @DisplayName("all four statuses are tiled even when three of them are empty")
        void everyStatusIsPresent() throws Exception {
            // Unlike the case list's facets, which drop empty values because a chip offering 0 is
            // a filter yielding an empty grid. Here the zero IS the information: no endpoint can
            // move a case out of DRAFT today, so "0 approved" is what a lead needs to see. A panel
            // that dropped it would show fewer tiles and look complete.
            assertThat(tileKeys(dashboardAs(LEAD, ACME, ACME_BANKING), "lifecycle"))
                    .containsExactly("DRAFT", "IN_REVIEW", "APPROVED", "DEPRECATED");
        }

        @Test
        @DisplayName("each tile's count equals the case list called with that tile's own filter")
        void tileCountsMatchTheListTheyOpen() throws Exception {
            // THE assertion of this class. A tile is a link into the case list, so its number and
            // the grid behind it must be the same query — asserted by actually calling the list
            // with the filter the tile emitted, rather than against a literal both could agree on
            // while both were wrong.
            JsonNode body = dashboardAs(LEAD, ACME, ACME_BANKING);

            for (JsonNode tile : body.get("lifecycle")) {
                assertThat(tile.get("count").asLong())
                        .as("tile %s promises %s cases; its own filter returns a different number",
                                tile.get("key").asText(), tile.get("count").asText())
                        .isEqualTo(listTotalFor(LEAD, ACME, ACME_BANKING, tile.get("filter")));
            }
        }

        @Test
        @DisplayName("a soft-deleted case and one in a soft-deleted suite are counted by neither")
        void deletedWorkIsAbsentFromTheCounts() throws Exception {
            // Not a separate rule here — it is CaseSpecifications', reached through the same
            // predicate. The assertion exists because it is exactly the agreement that would break
            // first if this endpoint ever grew a predicate of its own.
            assertThat(totalOf(dashboardAs(LEAD, ACME, ACME_BANKING)))
                    .as("TC-6 is deleted and TC-7 is live inside a deleted folder; neither counts")
                    .isEqualTo(liveCaseCount());
        }

        @Test
        @DisplayName("a tile carries the enum constant and no display text at all")
        void tilesCarryNoWords() throws Exception {
            // §180. `label` used to be here and to read "Draft". It was a second set of English
            // words for enums `lib/labels.ts` already names, and on the health half six of the ten
            // had drifted apart from it — so the assertion is now that the field is ABSENT, which
            // is the only form that fails if somebody re-adds one.
            JsonNode draft = tile(dashboardAs(LEAD, ACME, ACME_BANKING), "lifecycle", "DRAFT");

            assertThat(draft.get("key").asText()).isEqualTo("DRAFT");
            assertThat(draft.get("severity").asText()).isEqualTo("NEUTRAL");
            assertThat(draft.has("label"))
                    .as("a tile labels itself from its key, client-side — see DashboardTile in "
                            + "openapi.yaml")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Health tiles — empty, and saying so")
    class Health {

        @Test
        @DisplayName("no signal is tiled, because nothing writes case_health")
        void healthIsEmpty() throws Exception {
            // WHEN THIS TEST FAILS: something now computes health. That is the feature working;
            // assert the real signals rather than deleting the panel.
            assertThat(dashboardAs(LEAD, ACME, ACME_BANKING).get("health")).isEmpty();
        }

        @Test
        @DisplayName("healthComputedAt is null, which is what makes the empty panel honest")
        void neverComputedIsNotTheSameAsClean() throws Exception {
            // The §46 defect class, caught before it could ship. An empty "Needs attention" panel
            // is a CLAIM that nothing needs attention; with no writer for case_health that claim is
            // false in every deployment. Null is "nobody has looked" and the SPA renders a
            // different sentence for it.
            assertThat(dashboardAs(LEAD, ACME, ACME_BANKING).get("healthComputedAt").isNull())
                    .as("with case_health empty, a timestamp here would assert a measurement "
                            + "that never happened")
                    .isTrue();
        }
    }

    // =====================================================================
    // Breakdowns
    // =====================================================================

    @Nested
    @DisplayName("By feature — a breakdown, deliberately not a partition")
    class ByFeature {

        @Test
        @DisplayName("a case in two features appears in both bars, so the bars over-sum")
        void multiCardinalityIsNotDoubleCounting() throws Exception {
            // Feature is the one MULTI dimension (§6d). TC-1 covers Payments AND Refunds, so the
            // bars sum to more than the project's case count — which is the panel working, not a
            // defect. Attributing each case to one feature would need a rule invented here that
            // disagreed with every other reading of the same rows.
            JsonNode body = dashboardAs(LEAD, ACME, ACME_BANKING);

            assertThat(barTotal(body, "byFeature", F_PAYMENTS.toString())).isEqualTo(2);
            assertThat(barTotal(body, "byFeature", F_REFUNDS.toString())).isEqualTo(1);
        }

        @Test
        @DisplayName("Test Type and Test Level values never appear as feature bars")
        void oneJoinTableHoldsEveryDimension() throws Exception {
            // case_classification holds all three dimensions, so a query bounded only by the case
            // set silently bars "Functional" and "API" beside the features. The panel would still
            // render and still add up.
            assertThat(barLabels(dashboardAs(LEAD, ACME, ACME_BANKING), "byFeature"))
                    .containsExactlyInAnyOrder("Payments", "Payments › Refunds");
        }

        @Test
        @DisplayName("a nested feature is labelled by its path, not by its bare name")
        void hierarchyIsReadable() throws Exception {
            // Two features called "Refunds" under different parents are different things. The bare
            // name would render two identical bars carrying different numbers.
            assertThat(barLabels(dashboardAs(LEAD, ACME, ACME_BANKING), "byFeature"))
                    .contains("Payments › Refunds");
        }

        @Test
        @DisplayName("each segment's count equals the case list called with that segment's filter")
        void segmentCountsMatchTheListTheyOpen() throws Exception {
            assertSegmentsDrillCorrectly(dashboardAs(LEAD, ACME, ACME_BANKING), "byFeature");
        }

        @Test
        @DisplayName("a breakdown keeps its own label, and its segments carry no presentation")
        void barsKeepDataWordsAndSegmentsLoseRenderedOnes() throws Exception {
            // The line §180 draws, asserted on the one response where both sides of it appear. A
            // bar's label is DATA — a classification path or a person's name in this company, which
            // no client can resolve. A segment's key is a LifecycleStatus, so its label and its
            // colour were both renderings of a closed set the SPA already has tables for; the
            // colour was the worse of the two, because the server built "var(--color-draft)" by
            // concatenation while StatusChip.tsx held the identical string in `lifecycleVar`.
            JsonNode bar = dashboardAs(LEAD, ACME, ACME_BANKING).get("byFeature").get(0);

            assertThat(bar.get("label").asText()).isNotBlank();

            JsonNode segment = bar.get("segments").get(0);
            assertThat(segment.get("key").asText()).isIn("DRAFT", "IN_REVIEW", "APPROVED",
                    "DEPRECATED");
            assertThat(segment.has("label")).isFalse();
            assertThat(segment.has("color"))
                    .as("presentation for a closed enum does not travel; the client colours from "
                            + "the key")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("By owner — and the parameter it needed the case list to grow")
    class ByOwner {

        @Test
        @DisplayName("each segment's count equals the case list called with that segment's filter")
        void segmentCountsMatchTheListTheyOpen() throws Exception {
            // THE defect this slice found. The segments emitted ownerId, GET /cases did not bind
            // it, so the link dropped the owner and opened EVERYONE's cases in that status — under
            // a heading naming one person, with a correct number above it. Reading the code cannot
            // catch this; calling the link can. Deleting the ownerId parameter now fails here.
            assertSegmentsDrillCorrectly(dashboardAs(LEAD, ACME, ACME_BANKING), "byOwner");
        }

        @Test
        @DisplayName("one bar per owner, labelled by their name in THIS company")
        void ownersAreNamedPerCompany() throws Exception {
            // The membership's display name wins over the account's canonical one (§6u): the same
            // person is labelled separately per client and neither may see the other's label.
            assertThat(barLabels(dashboardAs(LEAD, ACME, ACME_BANKING), "byOwner"))
                    .containsExactlyInAnyOrder("L. Lead (Acme)", "Ana Author (Acme)");
        }

        @Test
        @DisplayName("bars are ordered by size, biggest first")
        void biggestOwnerFirst() throws Exception {
            // LEAD owns 3 live cases, ANA owns 1. A panel ordered by hash order reshuffles between
            // requests over unchanged data, which reads as the numbers moving.
            assertThat(barLabels(dashboardAs(LEAD, ACME, ACME_BANKING), "byOwner"))
                    .containsExactly("L. Lead (Acme)", "Ana Author (Acme)");
        }

        @Test
        @DisplayName("the unowned case is in no bar, and still in the tiles above")
        void unownedCasesAreCountedButNotBarred() throws Exception {
            // An "unassigned" bar would be the one row on the panel whose own link could not
            // reproduce it: ownerId= empty means "no opinion" over HTTP, so no value of the filter
            // selects it. The count is not lost — the lifecycle tiles count every case.
            JsonNode body = dashboardAs(LEAD, ACME, ACME_BANKING);

            long owned = 0;
            for (JsonNode bar : body.get("byOwner")) {
                owned += barTotalOf(bar);
            }
            assertThat(owned)
                    .as("TC-3 is unowned, so the by-owner bars fall one short of the tiles")
                    .isEqualTo(totalOf(body) - 1);
        }
    }

    // =====================================================================
    // Automation coverage
    // =====================================================================

    @Nested
    @DisplayName("Automation coverage — denominated against what can be automated")
    class Automation {

        @Test
        @DisplayName("NOT_AUTOMATABLE cases are out of the divisor")
        void honestlyDenominated() throws Exception {
            // 1 automated of (1 automated + 1 to-be + 3 manual) = 20%. With the not-automatable
            // case in the divisor it reads 16.7% — and, worse, marking another case impossible
            // would IMPROVE the number, so the metric would reward giving up (§6c).
            JsonNode coverage = dashboardAs(LEAD, ACME, ACME_BANKING).get("automationCoverage");

            assertThat(coverage.get("automated").asLong()).isEqualTo(1);
            assertThat(coverage.get("toBeAutomated").asLong()).isEqualTo(1);
            assertThat(coverage.get("manual").asLong()).isEqualTo(3);
            assertThat(coverage.get("notAutomatable").asLong()).isEqualTo(1);
            assertThat(coverage.get("coveragePercent").asDouble()).isEqualTo(20.0);
        }

        @Test
        @DisplayName("the four counts sum to the lifecycle tiles' total")
        void everyCaseHasExactlyOneAutomationStatus() throws Exception {
            // Two rollups of one set over two columns, on one screen. If they disagree there is
            // nothing on the page to identify the wrong one — §32's defect, and the reason this is
            // asserted as an equality between the two panels rather than against a literal.
            JsonNode body = dashboardAs(LEAD, ACME, ACME_BANKING);
            JsonNode coverage = body.get("automationCoverage");

            assertThat(coverage.get("automated").asLong()
                    + coverage.get("toBeAutomated").asLong()
                    + coverage.get("manual").asLong()
                    + coverage.get("notAutomatable").asLong())
                    .isEqualTo(totalOf(body));
        }
    }

    // =====================================================================
    // Activity
    // =====================================================================

    @Nested
    @DisplayName("Activity — thirty days, including the quiet ones")
    class Activity {

        @Test
        @DisplayName("thirty consecutive days ending today, with no gaps")
        void theAxisIsContiguous() throws Exception {
            // A chart drawn only from the days with activity compresses a quiet fortnight into no
            // space, so a burst three weeks ago sits next to today and the shape lies about the
            // tempo — the only thing a thirty-day chart is read for.
            List<String> dates = new ArrayList<>();
            dashboardAs(LEAD, ACME, ACME_BANKING).get("recentActivity")
                    .forEach(point -> dates.add(point.get("date").asText()));

            assertThat(dates).hasSize(30);
            assertThat(dates.get(29)).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
            for (int i = 1; i < dates.size(); i++) {
                assertThat(LocalDate.parse(dates.get(i)))
                        .as("day %d must follow day %d with no gap", i, i - 1)
                        .isEqualTo(LocalDate.parse(dates.get(i - 1)).plusDays(1));
            }
        }

        @Test
        @DisplayName("there is no approved series — nothing in the schema records an approval")
        void theApprovedSeriesIsDeliberatelyAbsent() throws Exception {
            // Narrowed out of the contract rather than served as a permanent zero. A flat zero line
            // states "nobody approved anything this month" about a client's work; absence states
            // "not measured". lifecycle_status is current state and audit_event.changes carries the
            // version number by design, so there is nothing to count.
            //
            // WHEN THIS TEST FAILS: the review workflow (§6g) has given approval a timestamp. Add
            // the series back to openapi.yaml, the SPA chart and here, together.
            for (JsonNode point : dashboardAs(LEAD, ACME, ACME_BANKING).get("recentActivity")) {
                assertThat(point.has("approved")).isFalse();
                assertThat(List.of("date", "created", "modified"))
                        .allSatisfy(field -> assertThat(point.has(field)).isTrue());
            }
        }

        @Test
        @DisplayName("a creation lands on its own day and a case older than the window does not")
        void creationsAreBucketedByDay() throws Exception {
            // TC-1 and TC-2 three days ago, TC-3 today, TC-4 and TC-5 ten days ago — and TC-8
            // forty days ago, which the chart must not show.
            //
            // Honest note on what this does NOT prove: deleting the `since` predicate in
            // DashboardTallies.creationTimes leaves this test green, because the buckets are built
            // by looking up the thirty days being plotted and an older timestamp lands on a key
            // nothing reads. So TC-8's absence is structural, and this assertion pins the day
            // arithmetic — the boundary, the bucketing, the totals — rather than that filter. See
            // that method's header, which records the surviving mutation rather than leaving the
            // predicate looking load-bearing.
            JsonNode body = dashboardAs(LEAD, ACME, ACME_BANKING);

            assertThat(activityOn(body, daysAgo(3), "created")).isEqualTo(2);
            assertThat(activityOn(body, daysAgo(10), "created")).isEqualTo(2);
            assertThat(activityOn(body, daysAgo(0), "created")).isEqualTo(1);
            assertThat(sumOf(body, "created"))
                    .as("five of the six live cases were created inside the window; TC-8 was not")
                    .isEqualTo(liveCaseCount() - 1);
        }

        @Test
        @DisplayName("version 1 is not a modification, so creating a case does not draw two lines")
        void creationIsNotAnEdit() throws Exception {
            // Since §29 a created case is written with its v1 in the SAME transaction. Counting
            // every version row makes "modified" shadow "created" exactly on every day nobody
            // edited anything — and it looks like data rather than like double counting.
            //
            // The fixture gives every live case a v1 and gives TC-1 alone a v2, two days ago.
            JsonNode body = dashboardAs(LEAD, ACME, ACME_BANKING);

            assertThat(sumOf(body, "modified"))
                    .as("eight v1 rows and two v2s; only the live case's v2 is a modification")
                    .isEqualTo(1);
            assertThat(activityOn(body, daysAgo(2), "modified")).isEqualTo(1);
        }

        @Test
        @DisplayName("edits to a deleted case leave the chart with the case")
        void deletedWorkIsAbsentFromTheChartToo() throws Exception {
            // TC-6 is soft-deleted and carries a v2 dated two days ago. It is excluded through the
            // same shared predicate as everything else — asserted because an activity query
            // written against test_case_version alone would have no reason to know about it.
            assertThat(activityOn(dashboardAs(LEAD, ACME, ACME_BANKING), daysAgo(2), "modified"))
                    .as("only TC-1's edit; TC-6's must not appear")
                    .isEqualTo(1);
        }
    }

    // =====================================================================
    // The contract check §47 said would have caught its own defect
    // =====================================================================

    @Nested
    @DisplayName("Every emitted filter key is a parameter the case list actually declares")
    class FilterKeysAreBound {

        /**
         * §6o makes every count the entrance to the list behind it. That rule has an edge which
         * cost this endpoint a defect: <strong>Spring silently ignores an unbound query
         * parameter</strong>, so a tile emitting a key {@code GET /cases} does not declare opens
         * a <em>wider</em> set than its own heading promises, with a correct number on the tile.
         * Nothing throws, no test fails, and the page looks right — §47 shipped
         * "Ana · Approved · 34" as a link to everyone's 210 approved cases before catching it.
         *
         * <p>The count assertions elsewhere in this class do not catch it on their own. They
         * compare a tile's count against the list its filter returns, which agrees perfectly
         * whenever the fixture has a single owner, a single feature, or one of anything — and a
         * fixture with one of something is the normal state of a freshly provisioned project.
         * This assertion is about the <em>key</em>, so it holds regardless of what the data
         * happens to look like.
         *
         * <p>It reads the emitted keys from a live response rather than from a list written here:
         * a literal list would be a second place to update, and it would agree with a wrong
         * implementation on the day someone added a key to only one of them.
         */
        @Test
        @DisplayName("no tile or segment emits a filter key GET /cases would ignore")
        void everyFilterKeyIsDeclaredByTheCaseList() throws Exception {
            Set<String> declared = OpenApiContract.load()
                    .queryParameterNames("/projects/{projectId}/cases");
            JsonNode body = dashboardAs(LEAD, ACME, ACME_BANKING);

            Set<String> emitted = new TreeSet<>();
            for (String panel : List.of("lifecycle", "health")) {
                body.get(panel).forEach(tile -> tile.get("filter").fieldNames()
                        .forEachRemaining(emitted::add));
            }
            for (String panel : List.of("byFeature", "byOwner")) {
                body.get(panel).forEach(bar -> bar.get("segments").forEach(
                        segment -> segment.get("filter").fieldNames().forEachRemaining(emitted::add)));
            }

            assertThat(emitted)
                    .as("the dashboard emitted no filters at all, so this asserted nothing")
                    .isNotEmpty();
            assertThat(emitted)
                    .as("""
                            Every key a dashboard filter emits must be a query parameter declared on \
                            GET /projects/{projectId}/cases. An undeclared key is not rejected — it is \
                            IGNORED, so the link opens an unfiltered superset under a heading that \
                            names the filter. Declared: %s""", declared)
                    .isSubsetOf(declared);
        }
    }

    // =====================================================================
    // helpers — reading the response
    // =====================================================================

    private JsonNode dashboardAs(UUID userId, UUID companyId, UUID projectId) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/dashboard")
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        JsonNode parsed = json.readTree(body);
        assertThat(parsed.isObject()).as("the endpoint must answer a JSON object: %s", body).isTrue();
        return parsed;
    }

    private int statusOf(UUID userId, UUID companyId, UUID projectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/dashboard")
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId)))
                .andReturn().getResponse().getStatus();
    }

    /**
     * The case list's {@code total} for one tile's or segment's own filter — the other half of
     * every drill-down assertion.
     *
     * <p>The filter is turned into query parameters exactly as the SPA does it: arrays are repeated
     * parameters, scalars are single ones. A key the case list does not bind is therefore simply
     * ignored by Spring, which is precisely the silent failure being tested for.
     */
    private long listTotalFor(UUID userId, UUID companyId, UUID projectId, JsonNode filter)
            throws Exception {

        StringBuilder query = new StringBuilder();
        filter.properties().forEach(entry -> {
            if (entry.getValue().isArray()) {
                entry.getValue().forEach(value ->
                        query.append('&').append(entry.getKey()).append('=').append(value.asText()));
            } else {
                query.append('&').append(entry.getKey()).append('=')
                        .append(entry.getValue().asText());
            }
        });

        String body = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/cases?size=200" + query)
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        return json.readTree(body).get("total").asLong();
    }

    /** Every segment of every bar in one panel drills to exactly the cases it counted. */
    private void assertSegmentsDrillCorrectly(JsonNode body, String panel) throws Exception {
        int segments = 0;
        for (JsonNode bar : body.get(panel)) {
            for (JsonNode segment : bar.get("segments")) {
                assertThat(segment.get("count").asLong())
                        .as("%s bar '%s' segment '%s' promises %s cases; its own filter returns "
                                        + "a different number",
                                panel, bar.get("label").asText(), segment.get("key").asText(),
                                segment.get("count").asText())
                        .isEqualTo(listTotalFor(LEAD, ACME, ACME_BANKING, segment.get("filter")));
                segments++;
            }
        }
        assertThat(segments)
                .as("a panel with no segments would pass the loop above without asserting anything")
                .isPositive();
    }

    private static long totalOf(JsonNode body) {
        long total = 0;
        for (JsonNode tile : body.get("lifecycle")) {
            total += tile.get("count").asLong();
        }
        return total;
    }

    private static List<String> tileKeys(JsonNode body, String panel) {
        List<String> keys = new ArrayList<>();
        body.get(panel).forEach(tile -> keys.add(tile.get("key").asText()));
        return keys;
    }

    private static JsonNode tile(JsonNode body, String panel, String key) {
        for (JsonNode tile : body.get(panel)) {
            if (tile.get("key").asText().equals(key)) {
                return tile;
            }
        }
        throw new AssertionError("no '" + key + "' tile — carried " + tileKeys(body, panel));
    }

    private static List<String> barLabels(JsonNode body, String panel) {
        List<String> labels = new ArrayList<>();
        body.get(panel).forEach(bar -> labels.add(bar.get("label").asText()));
        return labels;
    }

    private static long barTotal(JsonNode body, String panel, String id) {
        for (JsonNode bar : body.get(panel)) {
            if (bar.get("id").asText().equals(id)) {
                return barTotalOf(bar);
            }
        }
        return 0;
    }

    private static long barTotalOf(JsonNode bar) {
        long total = 0;
        for (JsonNode segment : bar.get("segments")) {
            total += segment.get("count").asLong();
        }
        return total;
    }

    private static long activityOn(JsonNode body, LocalDate day, String series) {
        for (JsonNode point : body.get("recentActivity")) {
            if (point.get("date").asText().equals(day.toString())) {
                return point.get(series).asLong();
            }
        }
        throw new AssertionError(day + " is not in the thirty-day window the response returned");
    }

    private static long sumOf(JsonNode body, String series) {
        long total = 0;
        for (JsonNode point : body.get("recentActivity")) {
            total += point.get(series).asLong();
        }
        return total;
    }

    private static LocalDate daysAgo(int days) {
        return LocalDate.now(ZoneOffset.UTC).minusDays(days);
    }

    /**
     * The live, reachable cases in ACME_BANKING — the number every rollup must land on.
     *
     * <p>Six, not five: TC-8 is live and merely <em>old</em>. It is counted by every tile and
     * every breakdown and appears in none of the activity buckets, which is the distinction the
     * chart's window exists to make and the one this fixture is built to catch.
     */
    private static long liveCaseCount() {
        return 6;
    }

    // =====================================================================
    // helpers — fixture
    // =====================================================================

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
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

    private void grantOnProject(
            String marker, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {
        UUID id = assignmentId(marker);
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private void grantOnCompany(String marker, UUID companyId, UUID roleId, UUID userId, Instant now) {
        UUID id = assignmentId(marker);
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(
                    RoleAssignment.forUserOnCompany(id, companyId, roleId, userId, null, now));
        }
    }

    private static UUID assignmentId(String marker) {
        return UUID.nameUUIDFromBytes(("dashboard:" + marker).getBytes());
    }

    private void seedVocabularies() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            call(connection, "SELECT tcms_seed_company_defaults(CAST(? AS uuid))", ACME);
            for (UUID projectId : List.of(ACME_BANKING, ACME_MOBILE, ACME_EMPTY)) {
                seedProjectDefaults(connection, ACME, projectId);
            }
            featureValue(connection, F_PAYMENTS, null, "Payments", "Payments", 0);
            featureValue(connection, F_REFUNDS, F_PAYMENTS, "Refunds", "Payments.Refunds", 1);
        }
        try (Connection connection = appConnection(UMBRA)) {
            call(connection, "SELECT tcms_seed_company_defaults(CAST(? AS uuid))", UMBRA);
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
        }
    }

    private void seedTrees() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            suite(connection, PAYMENTS, ACME, ACME_BANKING, null, "TS-1", 1, "Payments", false);
            suite(connection, CHECKOUT, ACME, ACME_BANKING, null, "TS-2", 2, "Checkout", false);
            suite(connection, RETIRED_SUITE, ACME, ACME_BANKING, null, "TS-3", 3, "Retired", true);
            suite(connection, MOBILE_SUITE, ACME, ACME_MOBILE, null, "TS-4", 4, "Payments", false);
        }
        try (Connection connection = appConnection(UMBRA)) {
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, null, "TS-1", 1, "Payments", false);
        }
    }

    /**
     * Five live cases in ACME_BANKING, plus the three that must not be counted.
     *
     * <p>Automation statuses are chosen so the coverage divisor and the raw total differ: one
     * automated, one to-be, two manual, one not-automatable. Creation dates are spread so a day
     * with two creations, a day with one, and a case older than the window are all distinguishable.
     */
    private void seedCases() throws SQLException {
        Instant today = Instant.now();

        try (Connection connection = appConnection(ACME)) {
            testCase(connection, caseId("tc1"), ACME_BANKING, PAYMENTS, "TC-1", 1,
                    "Full refund to card", "AUTOMATED", LEAD, false, ago(today, 3));
            testCase(connection, caseId("tc2"), ACME_BANKING, PAYMENTS, "TC-2", 2,
                    "Partial refund rounding", "MANUAL", LEAD, false, ago(today, 3));
            // Unowned — in the tiles, in no by-owner bar.
            testCase(connection, caseId("tc3"), ACME_BANKING, PAYMENTS, "TC-3", 3,
                    "Refund an expired card", "TO_BE_AUTOMATED", null, false, today);
            testCase(connection, caseId("tc4"), ACME_BANKING, CHECKOUT, "TC-4", 4,
                    "Guest checkout", "NOT_AUTOMATABLE", ANA, false, ago(today, 10));
            testCase(connection, caseId("tc5"), ACME_BANKING, CHECKOUT, "TC-5", 5,
                    "Split payment", "MANUAL", LEAD, false, ago(today, 10));

            // Soft-deleted, and carrying a v2 inside the window: absent from every count and from
            // the chart.
            testCase(connection, caseId("tc6"), ACME_BANKING, CHECKOUT, "TC-6", 6,
                    "Deleted case", "MANUAL", LEAD, true, ago(today, 5));
            // LIVE, inside a soft-deleted folder.
            testCase(connection, caseId("tc7"), ACME_BANKING, RETIRED_SUITE, "TC-7", 7,
                    "Case in a retired folder", "MANUAL", LEAD, false, ago(today, 5));
            // Older than the thirty-day window — counted in the tiles, absent from the chart.
            testCase(connection, caseId("tc8"), ACME_BANKING, PAYMENTS, "TC-8", 8,
                    "Ancient case", "MANUAL", LEAD, false, ago(today, 40));

            testCase(connection, caseId("mob1"), ACME_MOBILE, MOBILE_SUITE, "TC-20", 20,
                    "Mobile login", "MANUAL", LEAD, false, today);

            // v1 for every case — the row that must NOT count as a modification.
            for (String marker : List.of("tc1", "tc2", "tc3", "tc4", "tc5", "tc6", "tc7", "tc8")) {
                version(connection, caseId(marker), 1, ago(today, 3));
            }
            // The only real edits in the window: one on a live case, one on the deleted one.
            version(connection, caseId("tc1"), 2, ago(today, 2));
            version(connection, caseId("tc6"), 2, ago(today, 2));
        }
        try (Connection connection = appConnection(UMBRA)) {
            // TC-1 again — keys are per COMPANY, so the collision is the product working.
            testCase(connection, caseId("umb1"), UMBRA_BANKING, UMBRA_SUITE, "TC-1", 1,
                    "Full refund to card", "AUTOMATED", null, false, today);
        }
    }

    private void seedClassifications() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            // TC-1 on both parent and child: two bars, one case.
            classify(connection, caseId("tc1"), F_PAYMENTS);
            classify(connection, caseId("tc1"), F_REFUNDS);
            classify(connection, caseId("tc2"), F_PAYMENTS);
            // A Test Type and a Test Level on the same case — the rows that must not become
            // feature bars.
            classifyByName(connection, caseId("tc1"), "test_type", "Functional");
            classifyByName(connection, caseId("tc1"), "test_level", "API");
        }
    }

    private static UUID caseId(String marker) {
        return UUID.nameUUIDFromBytes(("dashboard-case:" + marker).getBytes());
    }

    private static Instant ago(Instant from, int days) {
        // Noon rather than midnight, so a bucket assertion cannot pass or fail on the boundary
        // between two UTC days while the test happens to run near one.
        return from.minus(days, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.DAYS)
                .plus(12, ChronoUnit.HOURS);
    }

    // =====================================================================
    // helpers — SQL
    // =====================================================================

    private void call(Connection connection, String sql, UUID argument) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, argument);
            statement.execute();
        }
    }

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {

        try (var statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setObject(1, companyId);
            statement.setObject(2, projectId);
            statement.execute();
        }
    }

    private void featureValue(
            Connection connection, UUID id, UUID parentId, String name, String path, int position)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO classification_value
                    (id, company_id, dimension_id, parent_id, path, name, position)
                SELECT CAST(? AS uuid), CAST(? AS uuid), d.id, CAST(? AS uuid), CAST(? AS ltree),
                       ?, ?
                  FROM classification_dimension d
                 WHERE d.company_id = CAST(? AS uuid)
                   AND d.project_id = CAST(? AS uuid)
                   AND d.key = 'feature'
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, parentId == null ? null : parentId.toString());
            statement.setString(4, path);
            statement.setString(5, name);
            statement.setInt(6, position);
            statement.setString(7, ACME.toString());
            statement.setString(8, ACME_BANKING.toString());
            statement.executeUpdate();
        }
    }

    private void suite(
            Connection connection, UUID id, UUID companyId, UUID projectId, UUID parentId,
            String key, long keySeq, String name, boolean deleted) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, key, key_seq, name, position, lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0, 'APPROVED', CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.setString(7, deleted ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    /**
     * One case, with an explicit {@code created_at} — which is the point.
     *
     * <p>Every case is left at {@code DRAFT}, matching the product: create fixes the status and no
     * endpoint can change it until the review workflow lands. The lifecycle panel's other three
     * tiles are therefore zero, and asserting that is the test rather than a limitation of it.
     */
    private void testCase(
            Connection connection, UUID id, UUID projectId, UUID suiteId, String key, long keySeq,
            String title, String automation, UUID ownerId, boolean deleted, Instant createdAt)
            throws SQLException {

        UUID companyId = projectId.equals(UMBRA_BANKING) ? UMBRA : ACME;
        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, not_automatable_reason,
                     owner_id, created_at, updated_at, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', ?, CAST(? AS text), CAST(? AS uuid),
                        CAST(? AS timestamptz), CAST(? AS timestamptz), CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, title);
            statement.setString(8, automation);
            statement.setString(9,
                    "NOT_AUTOMATABLE".equals(automation) ? "Third-party captcha" : null);
            statement.setString(10, ownerId == null ? null : ownerId.toString());
            statement.setString(11, createdAt.toString());
            statement.setString(12, createdAt.toString());
            statement.setString(13, deleted ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    /** A version row with an explicit {@code created_at} — the activity chart's second series. */
    private void version(Connection connection, UUID caseId, int versionNo, Instant createdAt)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case_version
                    (id, company_id, case_id, version_no, snapshot, content_hash, created_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?,
                        CAST('{}' AS jsonb), ?, CAST(? AS timestamptz))
                ON CONFLICT (case_id, version_no) DO NOTHING
                """)) {
            statement.setString(1,
                    UUID.nameUUIDFromBytes((caseId + ":v" + versionNo).getBytes()).toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, caseId.toString());
            statement.setInt(4, versionNo);
            statement.setString(5, "hash-" + versionNo);
            statement.setString(6, createdAt.toString());
            statement.executeUpdate();
        }
    }

    private void classify(Connection connection, UUID caseId, UUID valueId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO case_classification (company_id, case_id, value_id, dimension_id)
                SELECT CAST(? AS uuid), CAST(? AS uuid), v.id, v.dimension_id
                  FROM classification_value v
                 WHERE v.id = CAST(? AS uuid)
                ON CONFLICT (case_id, value_id) DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, valueId.toString());
            statement.executeUpdate();
        }
    }

    private void classifyByName(
            Connection connection, UUID caseId, String dimensionKey, String valueName)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO case_classification (company_id, case_id, value_id, dimension_id)
                SELECT CAST(? AS uuid), CAST(? AS uuid), v.id, v.dimension_id
                  FROM classification_value v
                  JOIN classification_dimension d ON d.id = v.dimension_id
                 WHERE d.company_id = CAST(? AS uuid)
                   AND d.project_id IS NULL
                   AND d.key = ?
                   AND v.name = ?
                ON CONFLICT (case_id, value_id) DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, ACME.toString());
            statement.setString(4, dimensionKey);
            statement.setString(5, valueName);
            statement.executeUpdate();
        }
    }
}
