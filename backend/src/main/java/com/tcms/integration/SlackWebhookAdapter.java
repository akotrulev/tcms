package com.tcms.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Slack, over an incoming webhook, as Block Kit.
 *
 * <h2>{@code text} is set as well as {@code blocks}, and it is not redundant</h2>
 *
 * <p>Slack renders {@code blocks} in the channel and uses {@code text} for the notification
 * preview — the line that appears on a phone's lock screen and in the desktop toast. A message
 * with blocks and no {@code text} pushes as "This content can't be displayed", which is the
 * failure mode where the notification is least likely to be looked at again and most likely to be
 * dismissed as broken.
 *
 * <h2>Facts go in one section's {@code fields}, ten at a time</h2>
 *
 * <p>Slack renders {@code fields} as a two-column grid and silently refuses a section with more
 * than ten of them — the whole message fails, not the extra fields. Nothing this product sends is
 * near that today; the cap is applied anyway, because the failure is a 400 for a channel that
 * worked yesterday and the cause is invisible from the delivery log.
 */
@Component
class SlackWebhookAdapter implements NotificationChannelPort {

    /** Slack's own limit on {@code fields} in one section. */
    private static final int MAX_FIELDS = 10;

    private final WebhookPoster poster;
    private final ObjectMapper json;

    SlackWebhookAdapter(WebhookPoster poster, ObjectMapper json) {
        this.poster = poster;
        this.json = json;
    }

    @Override
    public NotificationChannelKind kind() {
        return NotificationChannelKind.SLACK;
    }

    @Override
    public void deliver(RenderedNotification notification, ChannelConfig config) {
        poster.post(config.webhookUrl(), body(notification, json));
    }

    /**
     * The document, separated from the POST so it can be asserted without a server.
     *
     * <p>Built through Jackson rather than by string concatenation: a run named
     * {@code "Release "quick" fix"} composed into a JSON template is a payload Slack rejects, and
     * the name of a test run is user input.
     */
    static String body(RenderedNotification notification, ObjectMapper json) {
        ObjectNode root = json.createObjectNode();
        root.put("text", notification.title());

        ArrayNode blocks = root.putArray("blocks");

        ObjectNode header = blocks.addObject();
        header.put("type", "header");
        // plain_text, not mrkdwn: Slack refuses mrkdwn in a header block outright.
        header.putObject("text").put("type", "plain_text").put("text", notification.title());

        ObjectNode summary = blocks.addObject();
        summary.put("type", "section");
        summary.putObject("text").put("type", "mrkdwn").put("text", notification.summary());

        if (!notification.facts().isEmpty()) {
            ObjectNode facts = blocks.addObject();
            facts.put("type", "section");
            ArrayNode fields = facts.putArray("fields");
            notification.facts().stream()
                    .limit(MAX_FIELDS)
                    .forEach(fact -> fields.addObject()
                            .put("type", "mrkdwn")
                            .put("text", "*" + fact.label() + "*\n" + fact.value()));
        }
        return root.toString();
    }
}
