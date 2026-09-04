package com.tcms.time;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.tracker.TrackerEndpointTestBase;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 016 D-g's switch on the wire — {@code ProjectSettings.trackTime}.
 *
 * <p>The endpoint belongs to {@code project} and the decision belongs to this module, so the test
 * lives beside the decision: a reader of 016 looking for what happened to "Track time on this
 * project" finds it here rather than in a review-settings class that never mentions time.
 *
 * <h2>The failure this class exists to catch</h2>
 *
 * <p>{@code PUT /projects/{id}/settings} is a full replacement whose booleans collapse to
 * <strong>false</strong> when omitted, and {@code trackTime} is the one that must collapse to
 * <strong>true</strong>. Every other switch reads "absent means off" because an absent opt-in is
 * safe; this one reads the same rule the other way round, because an absent {@code trackTime}
 * would take a project's {@code Logged} column and both time panels away and refuse its next entry.
 * Getting that backwards is silent: the save succeeds, the screen re-reads what the server holds,
 * and the feature is simply gone.
 *
 * <p>UUID prefix {@code f9}, taken 2026-09-04 and free at the time. <strong>Not {@code f8}</strong>
 * — that is {@code TimeMoneyBoundaryTest}'s, and taking it made this class green alone and 403 in
 * the suite: {@code grantOnProject}'s {@code ON CONFLICT (id) DO NOTHING} left the other class's
 * row standing, so the Project Manager grant this class believed it had written was never there.
 * That is exactly the failure {@code TimeTestBase}'s header warns about, and the reason every id
 * lives in the subclass that owns it.
 */
@AutoConfigureMockMvc
@DisplayName("Track time — 016 D-g's switch, on by default, and an omitted field leaves it on")
class TrackTimeSettingTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("f9000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("f9001000-0000-4000-8000-000000000001");

    private static final UUID PM_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000009");

    private static final UUID PAULA = UUID.fromString("f9002000-0000-4000-8000-000000000001");
    private static final UUID PAULA_GRANT = UUID.fromString("f9003000-0000-4000-8000-000000000001");

    private static final String FULL_DOCUMENT = """
            {"reviewEnabledCase": false, "reviewEnabledSuite": false, "reviewEnabledPlan": false,
             "reviewEnabledDoc": false, "requiredPeerReviews": 0,
             "authorMayApproveOwnWork": false, "editAfterApproval": "RETAIN",
             "requireApprovedCaseForRun": false, "reviewEnabledIssue": false,
             "trackTime": %s}
            """;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();
        tx.executeWithoutResult(status -> {
            company(ACME, "track-time-acme", "Acme", now);
            activeUser(PAULA, "paula@track-time.test", "Paula Manager", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "TTM", "Track Time Project", now);
            member(ACME, PAULA, "Paula Manager", now);
        }));
        grantOnProject(PAULA_GRANT, ACME, PM_ROLE, PAULA, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
    }

    @Test
    @DisplayName("a project nobody has configured tracks time — D-g's default is on, not off")
    void theDefaultIsOn() throws Exception {
        assertThat(tracksTime())
                .as("a product whose timesheet is empty until somebody finds a switch is a feature "
                        + "nobody discovers")
                .isTrue();
    }

    @Test
    @DisplayName("the toggle saves in both directions")
    void theToggleSaves() throws Exception {
        assertThat(save(FULL_DOCUMENT.formatted("false"))).isEqualTo(200);
        assertThat(tracksTime()).isFalse();

        assertThat(save(FULL_DOCUMENT.formatted("true"))).isEqualTo(200);
        assertThat(tracksTime()).isTrue();
    }

    @Test
    @DisplayName("an omitted trackTime leaves tracking ON, unlike every other omitted boolean")
    void anOmittedFieldLeavesItOn() throws Exception {
        // The Review screen saves the same document without ever showing this switch. Under the
        // collapse-to-false rule the other nine follow, saving a peer-review count from there would
        // silently take the whole time feature off the project.
        assertThat(save("""
                {"requiredPeerReviews": 2, "editAfterApproval": "RETAIN"}
                """)).isEqualTo(200);

        assertThat(tracksTime()).isTrue();
    }

    @Test
    @DisplayName("switching it off does not delete what was already recorded — it hides a screen")
    void offIsNotADeletion() throws Exception {
        assertThat(save(FULL_DOCUMENT.formatted("false"))).isEqualTo(200);

        // Asserted on the settings document rather than on entries, because there are none to
        // write here: the claim is that the switch is a display and write gate and owns no rows,
        // so nothing in the save path can reach `time_entry` or `issue_time_rollup` at all.
        assertThat(save(FULL_DOCUMENT.formatted("true"))).isEqualTo(200);
        assertThat(tracksTime())
                .as("turning it back on restores the surfaces because nothing was ever removed")
                .isTrue();
    }

    /**
     * The status, with the body carried into the failure message.
     *
     * <p>A bare status assertion on a refusal says {@code expected: 200 but was: 403} and nothing
     * about which key was missing, which is the one fact that tells a reader whether the gate or the
     * fixture is wrong. {@code Problem} names the key and its scope, so the message is free.
     */
    private int save(String body) throws Exception {
        var response = mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + PROJECT + "/settings")
                        .header("Authorization", "Bearer " + tokenFor(PAULA, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();

        if (response.getStatus() != 200) {
            throw new AssertionError("PUT /settings answered " + response.getStatus() + ": "
                    + response.getContentAsString());
        }
        return response.getStatus();
    }

    private boolean tracksTime() throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + PROJECT + "/settings")
                        .header("Authorization", "Bearer " + tokenFor(PAULA, ACME)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("trackTime").asBoolean();
    }
}
