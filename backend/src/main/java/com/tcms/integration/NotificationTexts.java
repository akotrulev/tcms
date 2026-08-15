package com.tcms.integration;

import java.util.ArrayList;
import java.util.List;

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

        String who = assignee == null ? "someone who is no longer on the instance" : assignee;
        return new RenderedNotification(
                "Task assigned to " + who,
                payload.title(),
                List.of(new RenderedNotification.Fact("Project", projectName),
                        new RenderedNotification.Fact("Assignee", who)));
    }
}
