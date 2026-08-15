package com.tcms.integration;

import java.util.List;

/**
 * One notice, in the one shape all three transports render from.
 *
 * <h2>One model, three renderers — and the alternative is the failure being avoided</h2>
 *
 * <p>The obvious arrangement is a method per event type per transport, which is six today and
 * twelve when the next two events land. Every one of them is a place "run completed" can come to
 * mean something slightly different, and the divergence is invisible: nobody has all three
 * transports configured, so nobody ever sees two of them disagree. §6e uses this shape for reports
 * for the same reason.
 *
 * <p>So the event modules' payloads collapse into this, once, and each adapter's whole job is to
 * turn <em>this</em> into its own document. An adapter that needs a field this record does not
 * carry is a signal that the field belongs here rather than in that adapter.
 *
 * @param title   one line, the thing that happened. Rendered as the message's heading, and on a
 *                phone it is frequently the only part anybody reads — so it names the subject and
 *                the outcome, never the event type.
 * @param summary a sentence of context under the title. Never a repeat of the title: two renderings
 *                of one string looks like a bug in the message rather than emphasis.
 * @param facts   label/value pairs, in the order they should be read. A list rather than a map,
 *                because the order is part of the message and a map's is not the author's.
 */
record RenderedNotification(String title, String summary, List<Fact> facts) {

    /**
     * One labelled value.
     *
     * <p>Both halves are already display strings. Formatting here rather than in each adapter is
     * what stops a count rendering as {@code 3} in Slack and {@code 3.0} in an email.
     */
    record Fact(String label, String value) {
    }

    /** The whole message as plain text — SMTP's body, and every transport's fallback. */
    String asPlainText() {
        StringBuilder text = new StringBuilder(title).append("\n\n").append(summary);
        for (Fact fact : facts) {
            text.append('\n').append(fact.label()).append(": ").append(fact.value());
        }
        return text.toString();
    }
}
