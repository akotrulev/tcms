package com.tcms.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcms.identity.Role;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * {@code /issues/{key}/comments} and {@code /issues/{key}/watchers}.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A comment that does not make its author a watcher.</strong> Nothing fails: the
 *       comment is stored, the thread renders, and the person who wrote it never hears the reply.
 *       Story H8 is the whole reason the follow is in the same transaction as the comment.</li>
 *   <li><strong>{@code issue:comment} gated as {@code issue:edit}.</strong> That compiles, passes
 *       every happy-path test with a Lead, and silently removes a Viewer's only way to contribute —
 *       so the negative half is asserted beside the positive one.</li>
 *   <li><strong>A star that toggles instead of being set.</strong> Two clicks on a slow connection
 *       must not put it back where it started, in either direction.</li>
 *   <li><strong>A second comment failing on the watcher primary key</strong>, or restamping when
 *       the author started following — which reorders the watcher list.</li>
 * </ol>
 */
@AutoConfigureMockMvc
@DisplayName("Comments and watchers — saying something is following it")
class CommentEndpointTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("2e000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("2e001000-0000-4000-8000-000000000001");

    /** Holds the whole {@code issue:*} slice. */
    private static final UUID LEAD = UUID.fromString("2e002000-0000-4000-8000-000000000001");
    /** Holds {@code issue:read} and {@code issue:comment} and nothing that writes an issue. */
    private static final UUID VIEWER = UUID.fromString("2e002000-0000-4000-8000-000000000002");
    /** An ACTIVE Acme member granted on nothing at all. */
    private static final UUID NO_GRANT = UUID.fromString("2e002000-0000-4000-8000-000000000003");

    private static final UUID LEAD_GRANT = UUID.fromString("2e003000-0000-4000-8000-000000000001");
    private static final UUID VIEWER_GRANT = UUID.fromString("2e003000-0000-4000-8000-000000000002");

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "tracker-comment-acme", "Acme", now);
            activeUser(LEAD, "lead@tracker-comment.test", "Leonora Lead", now);
            activeUser(VIEWER, "viewer@tracker-comment.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@tracker-comment.test", "N. Grant", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "CMNT", "Comment Project", now);
            member(ACME, LEAD, "Leonora Lead", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);
        }));

        grantOnProject(LEAD_GRANT, ACME, Role.TEST_LEAD_ID, LEAD, PROJECT);
        grantOnProject(VIEWER_GRANT, ACME, Role.VIEWER_ID, VIEWER, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
        clearIssues(List.of(ACME));
    }

    @Test
    void commentingMakesYouAWatcher() throws Exception {
        // Story H8: a person who has said something is following it by default. Without this they
        // comment, the conversation continues, and they never hear about it — which is how a
        // comment thread on an issue tracker dies.
        String key = anIssue();

        comment(VIEWER, key, "Filing the expired-card failure under the epic.", 201);

        assertThat(watchersOf(key)).contains(VIEWER.toString());
    }

    @Test
    void aViewerMayCommentAndMayNotEdit() throws Exception {
        // §A3 gives Viewer issue:read and issue:comment and nothing else. The negative half is the
        // one worth asserting: a reader who can say something about the work must still not be able
        // to change it.
        String key = anIssue();

        assertThat(comment(VIEWER, key, "Is this the same as CMNT-1?", 201)).isNotNull();
        assertThat(patchTitleStatus(VIEWER, key)).isEqualTo(403);
    }

    @Test
    void watchingIsIdempotentInBothDirections() throws Exception {
        // A PUT with a boolean, not a POST and a DELETE: the star has two states and the request
        // says which one the person wants, not which one they think it is in. Two clicks on a slow
        // connection must not toggle it back.
        String key = anIssue();

        putWatch(LEAD, key, true);
        putWatch(LEAD, key, true);
        assertThat(watchersOf(key)).containsOnlyOnce(LEAD.toString());

        putWatch(LEAD, key, false);
        putWatch(LEAD, key, false);
        assertThat(watchersOf(key)).doesNotContain(LEAD.toString());
    }

    @Test
    void asecondCommentNeitherFailsNorRestampsTheFollow() throws Exception {
        String key = anIssue();

        comment(VIEWER, key, "First thought.", 201);
        comment(VIEWER, key, "Second thought.", 201);

        assertThat(watchersOf(key)).containsOnlyOnce(VIEWER.toString());
        assertThat(bodiesOf(comments(VIEWER, key)))
                .containsExactly("First thought.", "Second thought.");
    }

    @Test
    void unfollowingAfterCommentingIsAllowed() throws Exception {
        // The follow is a default, not a lock. Somebody who said one thing on a long thread must be
        // able to stop hearing about it without deleting what they said.
        String key = anIssue();
        comment(VIEWER, key, "One remark.", 201);

        putWatch(VIEWER, key, false);

        assertThat(watchersOf(key)).doesNotContain(VIEWER.toString());
        assertThat(comments(VIEWER, key)).hasSize(1);
    }

    @Test
    void aBlankCommentIsAFieldErrorRatherThanAnEmptyBubble() throws Exception {
        String key = anIssue();

        MvcResult refused = post(VIEWER, key, "   ").andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("Write something first");
    }

    @Test
    void anIssueTheCallerCannotSeeIsA404OnEveryOneOfTheseRoutes() throws Exception {
        // 404 and not 403 on all three: a caller with no grant must not be able to tell an issue
        // that exists elsewhere from one that does not exist (§6t).
        String key = anIssue();

        assertThat(post(NO_GRANT, key, "Hello").andReturn().getResponse().getStatus())
                .isEqualTo(404);
        assertThat(mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/issues/" + key + "/comments")
                        .header("Authorization", "Bearer " + tokenFor(NO_GRANT, ACME)))
                .andReturn().getResponse().getStatus())
                .isEqualTo(404);
        assertThat(watchStatus(NO_GRANT, key, true)).isEqualTo(404);
    }

    @Test
    void aWatchRequestThatSaysNothingIsRefusedRatherThanDefaultedToFalse() throws Exception {
        // Defaulting would silently unfollow the issue the caller was trying to follow.
        String key = anIssue();

        MvcResult refused = mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/issues/" + key + "/watchers")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("whether you want to follow");
    }

    @Test
    void theCommentCarriesItsAuthorsDisplayName() throws Exception {
        String key = anIssue();
        JsonNode written = comment(VIEWER, key, "Named, not just attributed to an id.", 201);

        assertThat(written.get("author").get("displayName").asText()).isEqualTo("V. Viewer");
        assertThat(written.get("editedAt").isNull()).isTrue();
    }

    // =====================================================================
    // Request helpers
    // =====================================================================

    private String anIssue() throws Exception {
        MvcResult workflow = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + PROJECT + "/workflow")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                .andReturn();
        assertThat(workflow.getResponse().getStatus())
                .as(workflow.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode types = json.readTree(workflow.getResponse().getContentAsString()).get("types");
        UUID storyType = null;
        for (JsonNode type : types) {
            if (type.get("name").asText().equals("Story")) {
                storyType = UUID.fromString(type.get("id").asText());
            }
        }
        assertThat(storyType).as("the seeded workflow has a Story type").isNotNull();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", PROJECT.toString());
        payload.put("typeId", storyType.toString());
        payload.put("title", "Expired cards are declined without a message");

        MvcResult created = mvc.perform(MockMvcRequestBuilders.post("/api/v1/issues")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return json.readTree(created.getResponse().getContentAsString()).get("key").asText();
    }

    private JsonNode comment(UUID userId, String key, String body, int expected) throws Exception {
        MvcResult result = post(userId, key, body).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(expected);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions post(
            UUID userId, String key, String body) throws Exception {

        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + key + "/comments")
                .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("body", body))));
    }

    private JsonNode comments(UUID userId, String key) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/issues/" + key + "/comments")
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void putWatch(UUID userId, String key, boolean watching) throws Exception {
        assertThat(watchStatus(userId, key, watching)).isEqualTo(200);
    }

    private int watchStatus(UUID userId, String key, boolean watching) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put("/api/v1/issues/" + key + "/watchers")
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("watching", watching))))
                .andReturn().getResponse().getStatus();
    }

    /** Read from the ISSUE, not from the PUT's response — the two must agree. */
    private List<String> watchersOf(String key) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.get("/api/v1/issues/" + key)
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        List<String> ids = new ArrayList<>();
        json.readTree(result.getResponse().getContentAsString())
                .get("watchers")
                .forEach(watcher -> ids.add(watcher.get("id").asText()));
        return ids;
    }

    private int patchTitleStatus(UUID userId, String key) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.patch("/api/v1/issues/" + key)
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Renamed\"}"))
                .andReturn().getResponse().getStatus();
    }

    private static List<String> bodiesOf(JsonNode list) {
        List<String> bodies = new ArrayList<>();
        list.forEach(comment -> bodies.add(comment.get("body").asText()));
        return bodies;
    }
}
