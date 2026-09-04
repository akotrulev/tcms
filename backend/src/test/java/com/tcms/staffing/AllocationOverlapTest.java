package com.tcms.staffing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 017 D-n at the wire: <strong>an allocation may not overlap itself, and the server refuses rather
 * than merging.</strong>
 *
 * <p>Two allocations of the same person to the same project over overlapping days are not a thing a
 * screen can render — {@code staffing.html} draws one bar per person per project — and merging them
 * silently would change a number somebody typed. So this asserts the {@code 409}, that its body
 * <strong>names the range it collided with</strong> (a refusal with no dates is one the caller
 * cannot act on), and the two boundaries a check like this gets wrong:
 *
 * <ol>
 *   <li><strong>A PATCH must not conflict with itself.</strong> The naive query finds the row being
 *       edited, every edit is a 409, and the failure looks like the rule working.</li>
 *   <li><strong>Two <em>different</em> projects over the same days are the ordinary case.</strong>
 *       That is precisely what over-allocation exists to report, so a check keyed on
 *       {@code (company, person)} rather than {@code (company, project, person)} would make the
 *       feature's main scenario impossible to enter.</li>
 * </ol>
 *
 * <p>UUID prefix {@code a6}, taken 2026-09-04 and free at the time.
 */
@AutoConfigureMockMvc
@DisplayName("Allocation overlap — a 409 that names the range, and neither false positive")
class AllocationOverlapTest extends StaffingTestBase {

    private static final UUID ACME = UUID.fromString("a6000000-0000-4000-8000-000000000001");
    private static final UUID LOYALTY = UUID.fromString("a6001000-0000-4000-8000-000000000001");
    private static final UUID PORTAL = UUID.fromString("a6001000-0000-4000-8000-000000000002");

    /** Project Manager on both projects — holds {@code staffing:allocate} and no company key. */
    private static final UUID PM = UUID.fromString("a6002000-0000-4000-8000-000000000001");
    private static final UUID DEV = UUID.fromString("a6002000-0000-4000-8000-000000000002");

    private static final UUID PM_LOYALTY_GRANT =
            UUID.fromString("a6003000-0000-4000-8000-000000000001");
    private static final UUID PM_PORTAL_GRANT =
            UUID.fromString("a6003000-0000-4000-8000-000000000002");

    private static final LocalDate MON = LocalDate.of(2026, 9, 7);
    private static final LocalDate TUE = LocalDate.of(2026, 9, 8);
    private static final LocalDate THU = LocalDate.of(2026, 9, 10);
    private static final LocalDate FRI = LocalDate.of(2026, 9, 11);

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "staffing-overlap-acme", "Acme", now);
            activeUser(PM, "pm@staffing-overlap.test", "P. Manager", now);
            activeUser(DEV, "dev@staffing-overlap.test", "D. Developer", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(LOYALTY, ACME, "OVLOY", "Acme Loyalty", now);
            seedProject(PORTAL, ACME, "OVPOR", "Bergstrom Portal", now);
            member(ACME, PM, "P. Manager", now);
            member(ACME, DEV, "D. Developer", now);
        }));
        grantOnProject(PM_LOYALTY_GRANT, ACME, PROJECT_MANAGER_ROLE, PM, LOYALTY);
        grantOnProject(PM_PORTAL_GRANT, ACME, PROJECT_MANAGER_ROLE, PM, PORTAL);

        wipeStaffing(ACME);
    }

    @Test
    @DisplayName("a second allocation over the same days is a 409 naming the range it hit")
    void overlappingCreateIsRefused() throws Exception {
        assertThat(allocate(LOYALTY, 50, MON, FRI).getResponse().getStatus()).isEqualTo(201);

        MvcResult refused = allocate(LOYALTY, 30, THU, FRI.plusDays(3));

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        JsonNode problem = json.readTree(refused.getResponse().getContentAsString());
        assertThat(problem.get("detail").asText())
                .contains(MON.toString())
                .contains(FRI.toString());
    }

    @Test
    @DisplayName("the same person on a DIFFERENT project over the same days is the ordinary case")
    void twoProjectsOverTheSameDaysAreAllowed() throws Exception {
        assertThat(allocate(LOYALTY, 50, MON, FRI).getResponse().getStatus()).isEqualTo(201);
        assertThat(allocate(PORTAL, 40, MON, FRI).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("a PATCH does not conflict with itself")
    void editingAnAllocationDoesNotCollideWithIt() throws Exception {
        UUID id = idOf(allocate(LOYALTY, 50, MON, FRI));

        MvcResult amended = mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + LOYALTY + "/allocations/" + id)
                        .header("Authorization", "Bearer " + tokenFor(PM, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"percent\":80}"))
                .andReturn();

        assertThat(amended.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(amended.getResponse().getContentAsString());
        assertThat(body.get("percent").asInt()).isEqualTo(80);
        // The absent fields were left alone, which is what makes this a delta rather than a PUT.
        assertThat(body.get("startsOn").asText()).isEqualTo(MON.toString());
        assertThat(body.get("endsOn").asText()).isEqualTo(FRI.toString());
    }

    @Test
    @DisplayName("but a PATCH that moves a range onto another allocation is a 409")
    void movingARangeOntoAnotherIsRefused() throws Exception {
        UUID first = idOf(allocate(LOYALTY, 50, MON, TUE));
        assertThat(allocate(LOYALTY, 30, THU, FRI).getResponse().getStatus()).isEqualTo(201);

        MvcResult refused = mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + LOYALTY + "/allocations/" + first)
                        .header("Authorization", "Bearer " + tokenFor(PM, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endsOn\":\"" + FRI + "\"}"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(refused.getResponse().getContentAsString())
                .get("detail").asText())
                .contains(THU.toString());
    }

    @Test
    @DisplayName("an allocation id from another project is a 404 on this one")
    void anAllocationIsAddressedThroughItsOwnProject() throws Exception {
        UUID onPortal = idOf(allocate(PORTAL, 50, MON, FRI));

        MvcResult wrongProject = mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + LOYALTY + "/allocations/" + onPortal)
                        .header("Authorization", "Bearer " + tokenFor(PM, ACME)))
                .andReturn();

        assertThat(wrongProject.getResponse().getStatus()).isEqualTo(404);
    }

    private MvcResult allocate(UUID projectId, int percent, LocalDate from, LocalDate to)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/allocations")
                        .header("Authorization", "Bearer " + tokenFor(PM, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"personId":"%s","percent":%d,"startsOn":"%s","endsOn":"%s"}"""
                                .formatted(DEV, percent, from, to)))
                .andReturn();
    }

    private UUID idOf(MvcResult created) throws Exception {
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(
                json.readTree(created.getResponse().getContentAsString()).get("id").asText());
    }
}
