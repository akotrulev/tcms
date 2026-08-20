package com.tcms.integration;

import com.tcms.project.ReviewSubjectDirectory;
import com.tcms.project.ReviewSubjectType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BinaryOperator;

/**
 * Turns an event's payload into the one model every transport renders from.
 *
 * <h2>Here, not in the adapters, and not in the handlers</h2>
 *
 * <p>This is the single place "a run finished" becomes words. Put in the adapters, it would be
 * three copies that only disagree in deployments where somebody has configured two channels — a
 * divergence with no observer. Put in the handlers, it would be correct and would put the product's
 * copy in the class whose job is transactions and idempotency.
 *
 * <p>Every value is formatted to a display string before it leaves here, for the same reason: a
 * count rendered by three adapters is three chances to render it differently.
 */
final class NotificationTexts {

    /**
     * What a message calls somebody whose account no longer resolves.
     *
     * <p>One constant rather than the string written out at each site: five renderings say it, and
     * five spellings of "we cannot name this person" is the divergence with no observer this class
     * exists to prevent.
     */
    private static final String NOBODY = "someone who is no longer on the instance";

    private NotificationTexts() {
    }

    /**
     * A finished run.
     *
     * <p>The title carries the verdict, not just the fact. "Run RUN-14 completed" tells a channel
     * nothing it has to act on; the failure count is the whole reason anybody subscribed, and on a
     * phone the title is frequently all that is read.
     *
     * @param projectName resolved by the handler — the payload carries a project id, and a channel
     *                    is per project, so the name is context the reader has and the message
     *                    would otherwise make them look up
     */
    static RenderedNotification runCompleted(RunCompletedPayload payload, String projectName) {
        String verdict = payload.failed() == 0
                ? "all green"
                : payload.failed() + (payload.failed() == 1 ? " failure" : " failures");

        List<RenderedNotification.Fact> facts = new ArrayList<>();
        facts.add(new RenderedNotification.Fact("Project", projectName));
        facts.add(new RenderedNotification.Fact("Passed", Integer.toString(payload.passed())));
        facts.add(new RenderedNotification.Fact("Failed", Integer.toString(payload.failed())));
        facts.add(new RenderedNotification.Fact("Blocked", Integer.toString(payload.blocked())));
        facts.add(new RenderedNotification.Fact("Executions", Integer.toString(payload.total())));

        return new RenderedNotification(
                payload.runKey() + " completed — " + verdict,
                payload.runName(),
                List.copyOf(facts));
    }

    /**
     * A task assigned to somebody.
     *
     * @param assignee the person's display name, or null when the id resolves to no account —
     *                 which is a real state, because {@code app_user} rows outlive nothing and a
     *                 person can be off-boarded between the assignment and the delivery. The
     *                 message then says the task was assigned and declines to say to whom, rather
     *                 than attributing it to somebody
     */
    static RenderedNotification itemAssigned(
            ItemAssignedPayload payload, String projectName, String assignee) {

        String who = assignee == null ? NOBODY : assignee;
        return new RenderedNotification(
                "Task assigned to " + who,
                payload.title(),
                List.of(new RenderedNotification.Fact("Project", projectName),
                        new RenderedNotification.Fact("Assignee", who)));
    }

    // ------------------------------------------------------------ the review workflow ----

    /**
     * Somebody was asked to review an artifact.
     *
     * <p>The title names <em>who</em>, because on a phone the title is frequently all that is read
     * and the one question this message answers is whether it is about the reader.
     *
     * @param subject the artifact, resolved at delivery, or null when it has been deleted since —
     *                see {@link #subjectOf}
     * @param askedOf the assignee's display name — a person's, or a team's. Exactly one of the
     *                payload's two ids is set, so the handler has already picked and this is never
     *                a choice between two names
     * @param askedBy who asked, or null when their account no longer resolves
     */
    static RenderedNotification reviewRequested(
            ReviewRequestedPayload payload,
            String projectName,
            ReviewSubjectDirectory.Subject subject,
            String askedOf,
            String askedBy) {

        String who = askedOf == null ? NOBODY : askedOf;
        String asker = askedBy == null ? NOBODY : askedBy;
        String artifact = subjectOf(subject, payload.subjectType());

        return new RenderedNotification(
                "Review requested from " + who,
                asker + " asked for a review of " + artifact,
                List.of(new RenderedNotification.Fact("Project", projectName),
                        new RenderedNotification.Fact("Reviewer", who),
                        new RenderedNotification.Fact("Asked by", asker),
                        new RenderedNotification.Fact(
                                capitalized(nounOf(payload.subjectType())), artifact)));
    }

    /**
     * A peer said the artifact looks good.
     *
     * <p>The title says "looks good to", never "approved": this is advisory, the sign-off is a
     * different act with a different permission, and a channel that read the two as one would
     * announce work as signed off that nobody holding {@code *:approve} has looked at.
     */
    static RenderedNotification reviewEndorsed(
            ReviewVerdictPayload payload,
            String projectName,
            ReviewSubjectDirectory.Subject subject,
            String reviewer,
            String submitter) {

        String who = reviewer == null ? NOBODY : reviewer;
        return verdict(payload, projectName, subject, who, submitter,
                "Looks good to " + who,
                (peer, artifact) -> peer + " thinks " + artifact + " looks good");
    }

    /** A peer asked for changes — the message the submitter has to act on. */
    static RenderedNotification reviewChangesRequested(
            ReviewVerdictPayload payload,
            String projectName,
            ReviewSubjectDirectory.Subject subject,
            String reviewer,
            String submitter) {

        String who = reviewer == null ? NOBODY : reviewer;
        return verdict(payload, projectName, subject, who, submitter,
                "Changes requested by " + who,
                (peer, artifact) -> peer + " asked for changes to " + artifact);
    }

    /**
     * A review was signed off.
     *
     * <p>The version is a fact rather than part of the title, and it is here at all because an
     * approval applies to the version that was submitted: an artifact edited since then has moved
     * past what anybody signed, which is §6g's diff-since-approval case.
     */
    static RenderedNotification reviewApproved(
            ReviewApprovedPayload payload,
            String projectName,
            ReviewSubjectDirectory.Subject subject,
            String approver,
            String submitter) {

        String who = approver == null ? NOBODY : approver;
        String artifact = subjectOf(subject, payload.subjectType());
        String noun = nounOf(payload.subjectType());

        List<RenderedNotification.Fact> facts = new ArrayList<>();
        facts.add(new RenderedNotification.Fact("Project", projectName));
        facts.add(new RenderedNotification.Fact(capitalized(noun), artifact));
        facts.add(new RenderedNotification.Fact("Approved by", who));
        facts.add(new RenderedNotification.Fact("Version", "v" + payload.subjectVersionNo()));
        if (submitter != null) {
            facts.add(new RenderedNotification.Fact("Submitted by", submitter));
        }

        return new RenderedNotification(
                capitalized(noun) + " approved — " + artifact,
                who + " signed off " + artifact,
                List.copyOf(facts));
    }

    /**
     * The two verdicts' shared body. They differ in one title and one sentence, and writing them
     * out twice is how the facts below come to be listed in two orders.
     *
     * @param summary given the reviewer's name and the artifact, the sentence under the title
     */
    private static RenderedNotification verdict(
            ReviewVerdictPayload payload,
            String projectName,
            ReviewSubjectDirectory.Subject subject,
            String reviewer,
            String submitter,
            String title,
            BinaryOperator<String> summary) {

        String artifact = subjectOf(subject, payload.subjectType());

        List<RenderedNotification.Fact> facts = new ArrayList<>();
        facts.add(new RenderedNotification.Fact("Project", projectName));
        facts.add(new RenderedNotification.Fact(
                capitalized(nounOf(payload.subjectType())), artifact));
        facts.add(new RenderedNotification.Fact("Reviewer", reviewer));
        // Omitted rather than rendered as "nobody": review.created_by is nullable, and a fact
        // saying the artifact was submitted by nobody invites the reader to go looking for a person
        // who was never recorded.
        if (submitter != null) {
            facts.add(new RenderedNotification.Fact("Submitted by", submitter));
        }
        if (payload.comment() != null && !payload.comment().isBlank()) {
            facts.add(new RenderedNotification.Fact("Comment", payload.comment()));
        }

        return new RenderedNotification(
                title, summary.apply(reviewer, artifact), List.copyOf(facts));
    }

    /**
     * The artifact, as a reader with nothing open can find it.
     *
     * @param subject the resolved key and title, or null when the artifact has been deleted since
     *                the review was written — a review is history and outlives its subject, so the
     *                message says what kind of thing it was and declines to name an id. A UUID in a
     *                sentence reads as a fault in the product ({@code NotificationProjects}' rule)
     */
    private static String subjectOf(
            ReviewSubjectDirectory.Subject subject, ReviewSubjectType subjectType) {

        if (subject == null) {
            return "a " + nounOf(subjectType) + " that has since been deleted";
        }
        return subject.key() + " — " + subject.title();
    }

    /** The artifact's kind, in the words the product's own screens use. */
    private static String nounOf(ReviewSubjectType subjectType) {
        return switch (subjectType) {
            case CASE -> "test case";
            case SUITE -> "suite";
            case PLAN -> "test plan";
        };
    }

    /** A fact's label is a heading, and a noun that reads as mid-sentence is not one. */
    private static String capitalized(String noun) {
        return Character.toUpperCase(noun.charAt(0)) + noun.substring(1);
    }
}
