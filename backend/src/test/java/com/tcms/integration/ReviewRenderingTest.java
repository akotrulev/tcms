package com.tcms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.tcms.project.ReviewSubjectDirectory;
import com.tcms.project.ReviewSubjectType;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the four review messages actually say — {@link NotificationRenderingTest}'s companion, and
 * in particular every arm where a row no longer resolves.
 *
 * <p>No Spring context: {@link NotificationTexts} is a pure function of a payload and four resolved
 * strings, which is the property that lets the copy be checked at all.
 *
 * <p>Ids are unique to this class — {@code b9…}.
 */
@DisplayName("Review notifications — what the message says, including when a row has gone")
class ReviewRenderingTest {

    private static final UUID ID = UUID.fromString("b9000000-0000-4000-8000-000000000001");
    private static final ReviewSubjectDirectory.Subject CASE_12 =
            new ReviewSubjectDirectory.Subject("CASE-12", "Login rejects a wrong password");

    @Test
    @DisplayName("a request names the reviewer in the title and the artifact in the summary")
    void theRequestNamesBoth() {
        RenderedNotification message = NotificationTexts.reviewRequested(
                new ReviewRequestedPayload(ID, ID, ID, ReviewSubjectType.CASE, ID, ID, null, ID),
                "Online Banking", CASE_12, "M. Reviewer", "L. Lead");

        assertThat(message.title()).isEqualTo("Review requested from M. Reviewer");
        assertThat(message.summary())
                .as("on a phone the title is frequently all that is read, so it answers whether "
                        + "this is about the reader and the summary says what about")
                .contains("L. Lead")
                .contains("CASE-12 — Login rejects a wrong password");
        assertThat(message.facts()).extracting(RenderedNotification.Fact::label)
                .containsExactly("Project", "Reviewer", "Asked by", "Test case");
    }

    @Test
    @DisplayName("a team request renders the team's name in the reviewer's place")
    void aTeamIsAReviewer() {
        RenderedNotification message = NotificationTexts.reviewRequested(
                new ReviewRequestedPayload(ID, ID, ID, ReviewSubjectType.CASE, ID, null, ID, ID),
                "Online Banking", CASE_12, "Payments QA", "L. Lead");

        assertThat(message.title())
                .as("the payload's two assignee arms are one name here — the handler picks")
                .isEqualTo("Review requested from Payments QA");
    }

    @Test
    @DisplayName("a deleted artifact is described by kind, never by id")
    void aDeletedSubjectIsNotAUuid() {
        RenderedNotification message = NotificationTexts.reviewApproved(
                new ReviewApprovedPayload(ID, ID, ID, ReviewSubjectType.PLAN, ID, 2, ID, null),
                "Online Banking", null, "L. Lead", null);

        assertThat(message.asPlainText())
                .as("a review is history and outlives its subject; a UUID in a sentence reads as "
                        + "a fault in the product")
                .contains("a test plan that has since been deleted")
                .doesNotContain(ID.toString());
    }

    @Test
    @DisplayName("an unresolvable person is named as such rather than left blank")
    void anOffboardedAskerIsStillASentence() {
        RenderedNotification message = NotificationTexts.reviewRequested(
                new ReviewRequestedPayload(ID, ID, ID, ReviewSubjectType.SUITE, ID, ID, null, ID),
                "Online Banking", CASE_12, null, null);

        assertThat(message.title()).isEqualTo("Review requested from someone who is no longer on "
                + "the instance");
        assertThat(message.summary()).startsWith("someone who is no longer on the instance asked");
    }

    @Test
    @DisplayName("an endorsement does not read as an approval")
    void theEndorsementIsNotASignOff() {
        RenderedNotification message = NotificationTexts.reviewEndorsed(
                new ReviewVerdictPayload(ID, ID, ID, ReviewSubjectType.CASE, ID, ID, ID, null),
                "Online Banking", CASE_12, "M. Reviewer", "A. Author");

        assertThat(message.title().toLowerCase(Locale.ROOT))
                .as("recording a peer verdict costs *:review and signing off costs *:approve — a "
                        + "message that conflated them would announce work as signed off that "
                        + "nobody with the approve key has looked at")
                .doesNotContain("approv");
        assertThat(message.facts()).extracting(RenderedNotification.Fact::label)
                .containsExactly("Project", "Test case", "Reviewer", "Submitted by");
    }

    @Test
    @DisplayName("a changes-requested verdict carries the peer's own words when there are any")
    void theCommentIsCarried() {
        RenderedNotification withComment = NotificationTexts.reviewChangesRequested(
                new ReviewVerdictPayload(ID, ID, ID, ReviewSubjectType.CASE, ID, ID, ID,
                        "Step 3 asserts nothing"),
                "Online Banking", CASE_12, "M. Reviewer", "A. Author");

        assertThat(withComment.title()).isEqualTo("Changes requested by M. Reviewer");
        assertThat(withComment.facts()).extracting(RenderedNotification.Fact::value)
                .contains("Step 3 asserts nothing");

        RenderedNotification without = NotificationTexts.reviewChangesRequested(
                new ReviewVerdictPayload(ID, ID, ID, ReviewSubjectType.CASE, ID, ID, ID, null),
                "Online Banking", CASE_12, "M. Reviewer", "A. Author");

        assertThat(without.facts()).extracting(RenderedNotification.Fact::label)
                .as("an empty Comment fact reads as a remark that was lost in transit")
                .doesNotContain("Comment");
    }

    @Test
    @DisplayName("an unattributable submitter is omitted rather than named as nobody")
    void nobodyIsNotAPerson() {
        RenderedNotification message = NotificationTexts.reviewChangesRequested(
                new ReviewVerdictPayload(ID, ID, ID, ReviewSubjectType.SUITE, ID, ID, null, null),
                "Online Banking", CASE_12, "M. Reviewer", null);

        assertThat(message.facts()).extracting(RenderedNotification.Fact::label)
                .as("a fact saying the artifact was submitted by nobody invites the reader to go "
                        + "looking for a person who was never recorded")
                .doesNotContain("Submitted by");
    }

    @Test
    @DisplayName("an approval names the version that was reviewed, and the artifact's kind")
    void theVersionIsAFact() {
        RenderedNotification message = NotificationTexts.reviewApproved(
                new ReviewApprovedPayload(ID, ID, ID, ReviewSubjectType.CASE, ID, 3, ID, ID),
                "Online Banking", CASE_12, "L. Lead", "A. Author");

        assertThat(message.title())
                .isEqualTo("Test case approved — CASE-12 — Login rejects a wrong password");
        assertThat(message.facts())
                .extracting(RenderedNotification.Fact::label, RenderedNotification.Fact::value)
                .contains(tuple("Version", "v3"))
                .as("stamped at submission and never re-stamped, so it is the version somebody "
                        + "was asked to read rather than whatever the artifact says now")
                .contains(tuple("Approved by", "L. Lead"));
    }
}
